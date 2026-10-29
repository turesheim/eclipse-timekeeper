package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Every intermediate version is checked in the transaction that applies it.
// Restores can target an older supported package; upgrades target currentSchema.
func schemaUpgradeSQL(resources string, from, to int) (string, error) {
	if !supportedSchema(from) || !supportedSchema(to) || from > to {
		return "", errors.New("Unsupported Timekeeper schema transition")
	}
	sql, err := schemaGuard(resources, from)
	if err != nil {
		return "", err
	}
	for version := from + 1; version <= to; version++ {
		name := map[int]string{3: "recovery-v3.sql", 4: "passkeys-v4.sql", 5: "settings-v5.sql", 6: "locale-v6.sql", 7: "day-labels-v7.sql", 8: "day-note-history-v8.sql", 9: "instance-v9.sql"}[version]
		if name == "" {
			return "", errors.New("Missing Timekeeper schema transition")
		}
		migration, err := os.ReadFile(filepath.Join(resources, name))
		if err != nil {
			return "", err
		}
		guard, err := schemaGuard(resources, version)
		if err != nil {
			return "", err
		}
		sql += string(migration) + fmt.Sprintf("\nUPDATE public.tk_schema SET schema_version=%d WHERE id=1;\n", version) + guard
	}
	return sql, nil
}

// An older dump cannot drop tables/constraints introduced after it was made.
// Check the current schema before removing only our known additions.
func prepareOlderRestoreSQL(resources string, current, incoming int) (string, error) {
	if !supportedSchema(incoming) || !supportedSchema(current) || incoming > current {
		return "", errors.New("Unsupported Timekeeper restore transition")
	}
	sql, err := schemaGuard(resources, current)
	if err != nil {
		return "", err
	}
	if current >= 9 && incoming < 9 {
		sql += "DROP TABLE public.tk_instance;\n"
	}
	if current >= 6 && incoming < 6 {
		sql += "DROP TABLE public.tk_locale;\n"
	}
	if current >= 5 && incoming < 5 {
		sql += "DROP TABLE public.tk_service_settings;\n"
	}
	if current >= 4 && incoming < 4 {
		sql += "ALTER TABLE public.tk_auth_session DROP CONSTRAINT tk_auth_session_passkey;\n" +
			"DROP TABLE public.tk_passkey_ceremony,public.tk_passkey_credential,public.tk_passkey_user;\n"
	}
	if current >= 3 && incoming < 3 {
		sql += "DROP TABLE public.tk_recovery_link,public.tk_recovery_address,public.tk_recovery_control;\n"
	}
	return sql, nil
}
