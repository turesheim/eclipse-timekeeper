package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const offlineSchemaSQL = "SET standard_conforming_strings=on; SELECT pg_advisory_xact_lock(92751638246);\n" +
	"DO $timekeeper_offline$ BEGIN IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()) THEN RAISE EXCEPTION 'Stop all other database clients before maintenance'; END IF; END $timekeeper_offline$;\n"

func schemaResources(resources string, version int) (string, string, error) {
	if !supportedSchema(version) {
		return "", "", errors.New("Unsupported schema version")
	}
	query, err := os.ReadFile(filepath.Join(resources, "postgresql-shape.sql"))
	if err != nil {
		return "", "", err
	}
	expected, err := os.ReadFile(filepath.Join(resources, fmt.Sprintf("schema-v%d-postgresql.shape", version)))
	return strings.TrimSuffix(strings.TrimSpace(string(query)), ";"), strings.TrimSpace(string(expected)), err
}

func canonicalSchema(s Settings, password, resources string, version int) error {
	query, expected, err := schemaResources(resources, version)
	if err != nil {
		return err
	}
	actual, err := psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, query)
	if err != nil {
		return err
	}
	if actual != expected {
		return errors.New("Database does not match the exact supported Timekeeper schema")
	}
	return nil
}

// Executed inside the same transaction as migration/restore, before COMMIT.
func schemaGuard(resources string, version int) (string, error) {
	query, expected, err := schemaResources(resources, version)
	if err != nil {
		return "", err
	}
	literal := "'" + strings.ReplaceAll(expected, "'", "''") + "'"
	// pg_dump clears search_path. Normalize it so catalog-rendered foreign keys
	// have the same representation as the committed canonical shape.
	return "\nSET search_path=public;\nDO $timekeeper_schema$ BEGIN IF (SELECT COALESCE(string_agg(definition,E'\\n' ORDER BY definition COLLATE \"C\"),'') FROM (" + query + ") canonical) <> " + literal + " THEN RAISE EXCEPTION 'Noncanonical Timekeeper schema'; END IF; " +
		fmt.Sprintf("IF (SELECT count(*) FROM public.tk_schema WHERE id=1 AND schema_version=%d AND state='READY')<>1 OR (SELECT count(*) FROM public.tk_schema)<>1 THEN RAISE EXCEPTION 'Incomplete Timekeeper schema'; END IF; ", version) + "END $timekeeper_schema$;\n", nil
}

func pendingSchemaUpgrade(root string) (maintenancePending, bool, error) {
	var pending maintenancePending
	err := readJSON(filepath.Join(root, "config/maintenance-pending.json"), &pending)
	if errors.Is(err, os.ErrNotExist) {
		return pending, false, nil
	}
	if err != nil {
		return pending, false, err
	}
	if pending.Operation != "schema-upgrade" {
		return pending, false, errors.New("Complete the interrupted maintenance operation before upgrading")
	}
	return pending, true, nil
}

func checkSchemaUpgrade(root, resources string) (Settings, string, int, error) {
	s, password, version, err := maintenanceConnection(root)
	if err != nil {
		return s, password, version, err
	}
	if err = canonicalSchema(s, password, resources, version); err != nil {
		return s, password, version, err
	}
	pending, exists, err := pendingSchemaUpgrade(root)
	if err != nil {
		return s, password, version, err
	}
	if version == currentSchema && !exists && s.Release.Schema != currentSchema {
		return s, password, version, errors.New("Restore the original upgrade journal before continuing")
	}
	if exists {
		// A retry must retain the exact pre-upgrade backup, including after DB commit.
		if !filepath.IsAbs(pending.ArchivePath) {
			return s, password, version, errors.New("Missing pre-upgrade backup path")
		}
		digest, readErr := fileDigest(pending.ArchivePath)
		if readErr != nil || digest != pending.ArchiveSHA256 {
			return s, password, version, errors.New("Restore the original pre-upgrade backup before continuing")
		}
	}
	return s, password, version, nil
}

func prepareSchemaUpgrade(root, resources string) error {
	s, password, version, err := checkSchemaUpgrade(root, resources)
	if err != nil {
		return err
	}
	if version == currentSchema {
		return nil
	}
	if !supportedUpgrade(version, currentSchema) {
		return errors.New("Unsupported Timekeeper schema upgrade")
	}
	_, pending, err := pendingSchemaUpgrade(root)
	if err != nil {
		return err
	}
	if !pending {
		archive, err := backup(root)
		if err != nil {
			return err
		}
		if err = beginMaintenance(root, archive, "schema-upgrade"); err != nil {
			return err
		}
	}
	migration, err := schemaUpgradeSQL(resources, version, currentSchema)
	if err != nil {
		return err
	}
	sql := offlineSchemaSQL + migration
	return pgRun(nativePSQL, s.DatabasePort, s.Role, password, s.Database, []string{"-X", "-q", "-w", "--single-transaction", "--file=-", "-v", "ON_ERROR_STOP=1"}, strings.NewReader(sql), io.Discard)
}
