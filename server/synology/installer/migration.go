package main

import (
	"archive/tar"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var migrationTables = []string{"tk_schema", "tk_owner", "tk_resource", "tk_relation", "tk_external_reference", "tk_active", "tk_receipt", "tk_change", "tk_account", "tk_auth_control", "tk_auth_challenge", "tk_api_token", "tk_auth_session", "tk_auth_throttle"}

type migrationManifest struct {
	Format                int               `json:"format"`
	Kind                  string            `json:"kind"`
	ServerSchema          int               `json:"serverSchema"`
	SourcePostgreSQLMajor int               `json:"sourcePostgreSQLMajor"`
	TargetPostgreSQLMajor int               `json:"targetPostgreSQLMajor"`
	CreatedAt             string            `json:"createdAt"`
	SchemaShapeSHA256     string            `json:"schemaShapeSha256"`
	TableFingerprints     map[string]string `json:"tableFingerprints"`
	Files                 map[string]string `json:"files"`
}

func unpackMigration(path, stage, resources string) (migrationManifest, error) {
	var manifest migrationManifest
	f, err := os.Open(path)
	if err != nil {
		return manifest, err
	}
	defer f.Close()
	reader := tar.NewReader(f)
	seen := map[string]bool{}
	for {
		h, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return manifest, err
		}
		if (h.Name != "manifest.json" && h.Name != "data.sql" && h.Name != "tokens.json") || seen[h.Name] || h.Typeflag != tar.TypeReg || (h.Name != "data.sql" && h.Size > 1048576) {
			return manifest, errors.New("Invalid PostgreSQL migration member")
		}
		seen[h.Name] = true
		out, err := os.OpenFile(filepath.Join(stage, h.Name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
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
	if len(seen) != 3 {
		return manifest, errors.New("Incomplete PostgreSQL migration archive")
	}
	if err = readJSON(filepath.Join(stage, "manifest.json"), &manifest); err != nil {
		return manifest, err
	}
	expected, err := fileDigest(filepath.Join(resources, "schema-v2-postgresql.shape"))
	if err != nil {
		return manifest, err
	}
	if manifest.Format != 1 || manifest.Kind != "timekeeper-postgresql18-to17" || manifest.ServerSchema != 2 || manifest.SourcePostgreSQLMajor != 18 || manifest.TargetPostgreSQLMajor != 17 || manifest.SchemaShapeSHA256 != expected || len(manifest.Files) != 2 || len(manifest.TableFingerprints) != len(migrationTables) {
		return manifest, errors.New("Unsupported PostgreSQL migration identity")
	}
	pattern := regexp.MustCompile(`^[0-9]+\|([a-f0-9]{32}|empty)$`)
	for _, table := range migrationTables {
		if !pattern.MatchString(manifest.TableFingerprints[table]) {
			return manifest, errors.New("Invalid migration table fingerprint")
		}
	}
	for _, name := range []string{"data.sql", "tokens.json"} {
		actual, err := fileDigest(filepath.Join(stage, name))
		if err != nil {
			return manifest, err
		}
		if actual != manifest.Files[name] {
			return manifest, errors.New("Migration archive checksum mismatch")
		}
	}
	return manifest, nil
}
func fingerprintQuery(table string) string {
	return "SELECT count(*)||'|'||COALESCE(md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text) COLLATE \"C\")),'empty') FROM public." + table + " t"
}
func importPostgreSQL18(root, path, resources string) error {
	s, password, err := maintenance(root)
	if err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Join(root, "backups"), ".migration-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	manifest, err := unpackMigration(path, stage, resources)
	if err != nil {
		return err
	}
	query := func(sql string) (string, error) {
		return psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, sql)
	}
	shapeSQL, err := os.ReadFile(filepath.Join(resources, "postgresql-shape.sql"))
	if err != nil {
		return err
	}
	expected, err := os.ReadFile(filepath.Join(resources, "schema-v2-postgresql.shape"))
	if err != nil {
		return err
	}
	actual, err := query(string(shapeSQL))
	if err != nil {
		return err
	}
	if actual != strings.TrimSpace(string(expected)) {
		return errors.New("Migration requires the exact canonical target schema 2")
	}
	_, pendingErr := os.Stat(filepath.Join(root, "config/maintenance-pending.json"))
	if errors.Is(pendingErr, os.ErrNotExist) {
		fresh, err := query("SELECT count(*) FROM public.tk_owner; SELECT bootstrap_completed FROM public.tk_auth_control WHERE id=1;")
		if err != nil {
			return err
		}
		if fresh != "0\nf" {
			return errors.New("Migration requires an unused target: do not create a target account or select an existing owner in its wizard")
		}
	}
	if err = beginMaintenance(root, path, "postgresql18-import"); err != nil {
		return err
	}
	tables := make([]string, len(migrationTables))
	for i, table := range migrationTables {
		tables[i] = "public." + table
	}
	before := "TRUNCATE " + strings.Join(tables, ",") + ";\n"
	after := "\nDO $timekeeper$ BEGIN\n"
	for _, table := range migrationTables {
		after += "IF (" + fingerprintQuery(table) + ") <> '" + manifest.TableFingerprints[table] + "' THEN RAISE EXCEPTION 'Migration data fingerprint mismatch'; END IF;\n"
	}
	after += "END $timekeeper$;\n"
	data, err := os.Open(filepath.Join(stage, "data.sql"))
	if err != nil {
		return err
	}
	defer data.Close()
	// A data-only PostgreSQL 18 dump is applied to our PostgreSQL 17 schema.
	// All table fingerprints must match before the single transaction commits.
	if err = pgRun(nativePSQL, s.DatabasePort, s.Role, password, s.Database,
		[]string{"-X", "-q", "-w", "--single-transaction", "--file=-", "-v", "ON_ERROR_STOP=1"},
		io.MultiReader(strings.NewReader(before), data, strings.NewReader(after)), io.Discard); err != nil {
		return err
	}
	tokens, err := os.ReadFile(filepath.Join(stage, "tokens.json"))
	if err != nil {
		return err
	}
	if err = atomic(filepath.Join(root, "config/tokens.json"), tokens, 0644); err != nil {
		return err
	}
	return finishMaintenance(root, s)
}

// Cancellation is safe only when the import transaction left the target unused.
// A committed import with unfinished configuration must instead be retried using
// the same archive; it must never be silently treated as a fresh installation.
func cancelEmptyImport(root string) error {
	s, password, err := maintenance(root)
	if err != nil {
		return err
	}
	path := filepath.Join(root, "config/maintenance-pending.json")
	var pending struct {
		Operation     string `json:"operation"`
		ArchiveSHA256 string `json:"archiveSha256"`
	}
	if err = readJSON(path, &pending); err != nil {
		return err
	}
	if pending.Operation != "postgresql18-import" {
		return errors.New("Only an empty failed migration can be cancelled")
	}
	value, err := psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, "SELECT count(*) FROM public.tk_owner; SELECT bootstrap_completed FROM public.tk_auth_control WHERE id=1;")
	if err != nil {
		return err
	}
	if value != "0\nf" {
		return errors.New("The imported database contains data; retry the original archive to complete migration")
	}
	if err = os.Remove(path); err != nil {
		return err
	}
	return syncMaintenanceMarker(root, packageTarget)
}
