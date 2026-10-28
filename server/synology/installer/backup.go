package main

import (
	"archive/tar"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type backupManifest struct {
	Format          int               `json:"format"`
	ServerSchema    int               `json:"serverSchema"`
	PostgreSQLMajor int               `json:"postgresqlMajor"`
	CreatedAt       string            `json:"createdAt"`
	Files           map[string]string `json:"files"`
}

var backupFiles = []string{"database.dump", "settings.json", "secrets/database_password", "config/tokens.json", "config/account-setup.json"}

func backupFilesFor(schema int) []string {
	files := append([]string{}, backupFiles...)
	if schema >= 3 {
		files = append(files, "config/mail.properties", "secrets/smtp_password")
	}
	return files
}

type maintenancePending struct {
	Operation     string `json:"operation"`
	ArchiveSHA256 string `json:"archiveSha256"`
	ArchivePath   string `json:"archivePath,omitempty"`
}

// Maintenance operations refuse a running listener or another database client.
// Package Center must stop Timekeeper first; this helper never kills processes.
func maintenance(root string) (Settings, string, error) {
	s, password, schema, err := maintenanceConnection(root)
	if err == nil && schema != s.Release.Schema {
		err = errors.New("Complete the pending schema upgrade before maintenance")
	}
	return s, password, err
}

func maintenanceConnection(root string) (Settings, string, int, error) {
	s, err := loadSettings(root)
	if err != nil {
		return s, "", 0, err
	}
	if s.Phase != "READY" {
		return s, "", 0, errors.New("Complete package installation before maintenance")
	}
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", s.HTTPPort))
	if err != nil {
		return s, "", 0, errors.New("Stop Timekeeper in Package Center before maintenance")
	}
	listener.Close()
	raw, err := os.ReadFile(filepath.Join(root, "secrets/database_password"))
	if err != nil {
		return s, "", 0, err
	}
	password := strings.TrimSpace(string(raw))
	query := "SELECT current_setting('server_version_num')::int/10000; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid(); SELECT schema_version||'|'||state FROM tk_schema WHERE id=1;"
	value, err := psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, query)
	if err != nil {
		return s, "", 0, err
	}
	schema := 0
	for version := 2; version <= currentSchema; version++ {
		if value == fmt.Sprintf("17\n0\n%d|READY", version) {
			schema = version
		}
	}
	if schema == 0 {
		return s, "", 0, errors.New("Maintenance requires PostgreSQL 17, a supported ready schema and all other database clients stopped")
	}
	return s, password, schema, nil
}
func fileDigest(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	hash := sha256.New()
	_, err = io.Copy(hash, f)
	return hex.EncodeToString(hash.Sum(nil)), err
}
func backup(root string) (string, error) {
	s, password, err := maintenance(root)
	if err != nil {
		return "", err
	}
	if _, err = os.Stat(filepath.Join(root, "config/maintenance-pending.json")); !errors.Is(err, os.ErrNotExist) {
		return "", errors.New("Complete the interrupted maintenance operation before making a new backup")
	}
	material, err := nativeMaterialControl(root, s, password)
	if err != nil {
		return "", err
	}
	files := backupFilesFor(s.Release.Schema)
	if material != nil {
		files = append(files, material.Files...)
	}
	base := filepath.Join(root, "backups")
	if err = os.MkdirAll(base, 0700); err != nil {
		return "", err
	}
	stage, err := os.MkdirTemp(base, ".backup-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(stage)
	dump, err := os.OpenFile(filepath.Join(stage, "database.dump"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	err = pgRun(filepath.Join(filepath.Dir(nativePSQL), "pg_dump"), s.DatabasePort, s.Role, password, s.Database,
		[]string{"--no-password", "--format=custom", "--no-owner", "--no-privileges"}, nil, dump)
	closeErr := dump.Close()
	if err != nil {
		return "", err
	}
	if closeErr != nil {
		return "", closeErr
	}
	format := 1
	if material != nil {
		format = 2
	}
	manifest := backupManifest{Format: format, ServerSchema: s.Release.Schema, PostgreSQLMajor: 17, CreatedAt: time.Now().UTC().Format(time.RFC3339Nano), Files: map[string]string{}}
	for _, name := range files {
		source := filepath.Join(root, name)
		if name == "database.dump" {
			source = filepath.Join(stage, name)
		}
		digest, err := fileDigest(source)
		if err != nil {
			return "", err
		}
		manifest.Files[name] = digest
	}
	result, err := os.CreateTemp(base, "timekeeper-*.tar")
	if err != nil {
		return "", err
	}
	path := result.Name()
	complete := false
	defer func() {
		result.Close()
		if !complete {
			os.Remove(path)
		}
	}()
	writer := tar.NewWriter(result)
	content, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return "", err
	}
	if err = writer.WriteHeader(&tar.Header{Name: "manifest.json", Mode: 0600, Size: int64(len(content))}); err != nil {
		return "", err
	}
	if _, err = writer.Write(content); err != nil {
		return "", err
	}
	for _, name := range files {
		source := filepath.Join(root, name)
		if name == "database.dump" {
			source = filepath.Join(stage, name)
		}
		f, err := os.Open(source)
		if err != nil {
			return "", err
		}
		info, err := f.Stat()
		if err != nil {
			f.Close()
			return "", err
		}
		err = writer.WriteHeader(&tar.Header{Name: name, Mode: 0600, Size: info.Size()})
		if err == nil {
			_, err = io.Copy(writer, f)
		}
		f.Close()
		if err != nil {
			return "", err
		}
	}
	if err = writer.Close(); err != nil {
		return "", err
	}
	if err = result.Sync(); err != nil {
		return "", err
	}
	complete = true
	return path, nil
}
func unpackBackup(path, stage string) (backupManifest, error) {
	var manifest backupManifest
	allowed := map[string]bool{"manifest.json": true}
	for _, name := range backupFilesFor(currentSchema) {
		allowed[name] = true
	}
	f, err := os.Open(path)
	if err != nil {
		return manifest, err
	}
	defer f.Close()
	reader := tar.NewReader(f)
	seen := map[string]bool{}
	var materialBytes int64
	for {
		header, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return manifest, err
		}
		limit := int64(1048576)
		if validNativeMaterialMember(header.Name) {
			limit = 20_000_000
			switch {
			case header.Name == "instance-restore/active.json":
				limit = 1024
			case strings.HasSuffix(header.Name, "/material.json"):
				limit = 131072
			case strings.HasSuffix(header.Name, "/application.properties"):
				limit = 1_000_000
			case strings.HasSuffix(header.Name, "/source-configuration.json"):
				limit = 4_000_000
			}
			if header.Size < 0 || header.Size > 70_133_096-materialBytes {
				return manifest, errors.New("Native instance material is too large")
			}
			materialBytes += header.Size
		}
		if (!allowed[header.Name] && !validNativeMaterialMember(header.Name)) || len(seen) >= 80 || seen[header.Name] || header.Typeflag != tar.TypeReg || header.Size < 0 || (header.Name != "database.dump" && header.Size > limit) {
			return manifest, errors.New("Invalid Timekeeper backup member")
		}
		seen[header.Name] = true
		destination := filepath.Join(stage, header.Name)
		if err = os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
			return manifest, err
		}
		out, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			return manifest, err
		}
		_, err = io.Copy(out, reader)
		if err == nil {
			err = out.Sync()
		}
		closeErr := out.Close()
		if err != nil {
			return manifest, err
		}
		if closeErr != nil {
			return manifest, closeErr
		}
	}
	if err = readJSON(filepath.Join(stage, "manifest.json"), &manifest); err != nil {
		return manifest, err
	}
	files := backupFilesFor(manifest.ServerSchema)
	if (manifest.Format != 1 && manifest.Format != 2) || !supportedSchema(manifest.ServerSchema) || manifest.PostgreSQLMajor != 17 {
		return manifest, errors.New("Unsupported Timekeeper backup version")
	}
	material, err := nativeMaterialInventory(stage)
	if err != nil {
		return manifest, err
	}
	if material != nil {
		if manifest.Format != 2 || manifest.ServerSchema < 9 {
			return manifest, errors.New("Unsupported native instance material")
		}
		files = append(files, material.Files...)
	}
	if len(manifest.Files) != len(files) || len(seen) != len(files)+1 {
		return manifest, errors.New("Incomplete Timekeeper backup")
	}
	for _, name := range files {
		digest, err := fileDigest(filepath.Join(stage, name))
		if err != nil {
			return manifest, err
		}
		if !seen[name] || digest != manifest.Files[name] {
			return manifest, errors.New("Timekeeper backup checksum mismatch")
		}
	}
	return manifest, nil
}
func restore(root, path, resources string) error {
	s, password, err := maintenance(root)
	if err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Join(root, "backups"), ".restore-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	manifest, err := unpackBackup(path, stage)
	if err != nil {
		return err
	}
	if manifest.ServerSchema > s.Release.Schema {
		return errors.New("Install a package compatible with this newer backup before restoring")
	}
	material, err := nativeMaterialInventory(stage)
	if err != nil {
		return err
	}
	if err = installNativeMaterial(root, stage, material); err != nil {
		return err
	}
	if err = beginMaintenance(root, path, "native-restore"); err != nil {
		return err
	}
	dump, err := os.Open(filepath.Join(stage, "database.dump"))
	if err != nil {
		return err
	}
	defer dump.Close()
	// Render into a private temporary file, then restore and any supported upgrade
	// in ONE psql transaction. An older dump does not know which new tables to drop.
	// Neither SQL data nor credential hashes are written to routine logs.
	sqlPath := filepath.Join(stage, "restore.sql")
	sqlFile, err := os.OpenFile(sqlPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if err = pgRun(filepath.Join(filepath.Dir(nativePSQL), "pg_restore"), s.DatabasePort, s.Role, password, s.Database,
		[]string{"--no-password", "--clean", "--if-exists", "--no-owner", "--no-privileges", "--exit-on-error", "--file=-"}, dump, sqlFile); err != nil {
		sqlFile.Close()
		return err
	}
	if err = sqlFile.Close(); err != nil {
		return err
	}
	reader, err := os.Open(sqlPath)
	if err != nil {
		return err
	}
	defer reader.Close()
	cleanup, err := prepareOlderRestoreSQL(resources, s.Release.Schema, manifest.ServerSchema)
	if err != nil {
		return err
	}
	upgrade, err := schemaUpgradeSQL(resources, manifest.ServerSchema, s.Release.Schema)
	if err != nil {
		return err
	}
	before, after := offlineSchemaSQL+cleanup, "\nSET search_path=public;\n"+upgrade+nativeMaterialRestoreSQL(material, s.Release.Schema)
	if err = pgRun(nativePSQL, s.DatabasePort, s.Role, password, s.Database,
		[]string{"-X", "-q", "-w", "--single-transaction", "--file=-", "-v", "ON_ERROR_STOP=1"},
		io.MultiReader(strings.NewReader(before), reader, strings.NewReader(after)), io.Discard); err != nil {
		return err
	}
	configuration := []string{"config/tokens.json", "config/account-setup.json"}
	if manifest.ServerSchema >= 3 {
		configuration = append(configuration, "config/mail.properties", "secrets/smtp_password")
	}
	for _, name := range configuration {
		content, err := os.ReadFile(filepath.Join(stage, name))
		if err != nil {
			return err
		}
		mode := os.FileMode(0644)
		if strings.HasPrefix(name, "secrets/") {
			mode = 0444
		}
		if err = atomic(filepath.Join(root, name), content, mode); err != nil {
			return err
		}
	}
	if err = publishNativeMaterial(root, stage, material); err != nil {
		return err
	}
	return finishMaintenance(root, s)
}

func beginMaintenance(root, path, operation string) error {
	digest, err := fileDigest(path)
	if err != nil {
		return err
	}
	journal := filepath.Join(root, "config/maintenance-pending.json")
	var retained maintenancePending
	err = readJSON(journal, &retained)
	if err == nil {
		if retained.Operation == operation && operation == "native-restore" {
			if err = writeJSON(journal, maintenancePending{operation, digest, path}, 0644); err != nil {
				return err
			}
			return syncMaintenanceMarker(root, packageTarget)
		}
		if retained.Operation != operation || retained.ArchiveSHA256 != digest {
			return errors.New("Retry the same archive and operation to complete interrupted maintenance")
		}
		return syncMaintenanceMarker(root, packageTarget)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return errors.New("Restore the original maintenance journal before proceeding")
	}
	if err = writeJSON(journal, maintenancePending{operation, digest, path}, 0644); err != nil {
		return err
	}
	return syncMaintenanceMarker(root, packageTarget)
}
