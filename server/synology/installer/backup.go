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

// Maintenance operations refuse a running listener or another database client.
// Package Center must stop Timekeeper first; this helper never kills processes.
func maintenance(root string) (Settings, string, error) {
	s, err := loadSettings(root)
	if err != nil {
		return s, "", err
	}
	if s.Phase != "READY" {
		return s, "", errors.New("Complete package installation before maintenance")
	}
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", s.HTTPPort))
	if err != nil {
		return s, "", errors.New("Stop Timekeeper in Package Center before maintenance")
	}
	listener.Close()
	raw, err := os.ReadFile(filepath.Join(root, "secrets/database_password"))
	if err != nil {
		return s, "", err
	}
	password := strings.TrimSpace(string(raw))
	query := "SELECT current_setting('server_version_num')::int/10000; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid(); SELECT schema_version||'|'||state FROM tk_schema WHERE id=1;"
	value, err := psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, query)
	if err != nil {
		return s, "", err
	}
	if value != "17\n0\n2|READY" {
		return s, "", errors.New("Maintenance requires PostgreSQL 17, a ready schema 2 and all other database clients stopped")
	}
	return s, password, nil
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
	manifest := backupManifest{Format: 1, ServerSchema: s.Release.Schema, PostgreSQLMajor: 17, CreatedAt: time.Now().UTC().Format(time.RFC3339Nano), Files: map[string]string{}}
	for _, name := range backupFiles {
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
	for _, name := range backupFiles {
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
	for _, name := range backupFiles {
		allowed[name] = true
	}
	f, err := os.Open(path)
	if err != nil {
		return manifest, err
	}
	defer f.Close()
	reader := tar.NewReader(f)
	seen := map[string]bool{}
	for {
		header, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return manifest, err
		}
		if !allowed[header.Name] || seen[header.Name] || header.Typeflag != tar.TypeReg || (header.Name != "database.dump" && header.Size > 1048576) {
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
		closeErr := out.Close()
		if err != nil {
			return manifest, err
		}
		if closeErr != nil {
			return manifest, closeErr
		}
	}
	if len(seen) != len(allowed) {
		return manifest, errors.New("Incomplete Timekeeper backup")
	}
	if err = readJSON(filepath.Join(stage, "manifest.json"), &manifest); err != nil {
		return manifest, err
	}
	if manifest.Format != 1 || manifest.ServerSchema != 2 || manifest.PostgreSQLMajor != 17 || len(manifest.Files) != len(backupFiles) {
		return manifest, errors.New("Unsupported Timekeeper backup version")
	}
	for _, name := range backupFiles {
		digest, err := fileDigest(filepath.Join(stage, name))
		if err != nil {
			return manifest, err
		}
		if digest != manifest.Files[name] {
			return manifest, errors.New("Timekeeper backup checksum mismatch")
		}
	}
	return manifest, nil
}
func restore(root, path string) error {
	s, password, err := maintenance(root)
	if err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Join(root, "backups"), ".restore-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	if _, err = unpackBackup(path, stage); err != nil {
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
	// The existing role owns restored objects; source cluster roles and passwords
	// are never changed. pg_restore rolls the database back on any error.
	if err = pgRun(filepath.Join(filepath.Dir(nativePSQL), "pg_restore"), s.DatabasePort, s.Role, password, s.Database,
		[]string{"--no-password", "--dbname=" + s.Database, "--clean", "--if-exists", "--no-owner", "--no-privileges", "--exit-on-error", "--single-transaction"}, dump, io.Discard); err != nil {
		return err
	}
	for _, name := range []string{"config/tokens.json", "config/account-setup.json"} {
		content, err := os.ReadFile(filepath.Join(stage, name))
		if err != nil {
			return err
		}
		if err = atomic(filepath.Join(root, name), content, 0644); err != nil {
			return err
		}
	}
	return os.Remove(filepath.Join(root, "config/maintenance-pending.json"))
}

func beginMaintenance(root, path, operation string) error {
	digest, err := fileDigest(path)
	if err != nil {
		return err
	}
	journal := filepath.Join(root, "config/maintenance-pending.json")
	type pending struct {
		Operation     string `json:"operation"`
		ArchiveSHA256 string `json:"archiveSha256"`
	}
	var retained pending
	err = readJSON(journal, &retained)
	if err == nil {
		if retained.Operation == operation && operation == "native-restore" {
			return writeJSON(journal, pending{operation, digest}, 0644)
		}
		if retained.Operation != operation || retained.ArchiveSHA256 != digest {
			return errors.New("Retry the same archive and operation to complete interrupted maintenance")
		}
		return nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return errors.New("Restore the original maintenance journal before proceeding")
	}
	return writeJSON(journal, pending{operation, digest}, 0644)
}
