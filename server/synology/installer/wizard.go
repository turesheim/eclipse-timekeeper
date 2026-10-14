package main

import (
	"encoding/json"
	"io"
)

func field(kind, key, label, value string, required bool) map[string]any {
	return map[string]any{"type": kind, "desc": label, "subitems": []any{map[string]any{"key": key, "desc": label, "defaultValue": value, "validator": map[string]any{"allowBlank": !required}}}}
}
func wizard(output io.Writer) error {
	code, err := secret()
	if err != nil {
		return err
	}
	steps := []any{map[string]any{"step_title": "Timekeeper server", "items": []any{map[string]any{"desc": "Install SynoCommunity PostgreSQL and Container Manager first. Administrator credentials are used only to create a dedicated non-superuser database role. The timekeeper-data shared folder and database are retained when this package is removed."}, field("textfield", "wizard_database_admin", "PostgreSQL administrator", "pgadmin", true), field("password", "wizard_database_admin_password", "PostgreSQL administrator password", "", true), field("textfield", "wizard_database_port", "SynoCommunity PostgreSQL port", "5433", true), field("textfield", "wizard_database", "Dedicated database name", "timekeeper", true), field("textfield", "wizard_database_role", "Dedicated application role", "timekeeper_app", true)}}, map[string]any{"step_title": "Web access and first account", "items": []any{field("textfield", "wizard_http_port", "Local HTTP port", "8080", true), field("textfield", "wizard_public_origin", "Public HTTPS origin (DSM reverse proxy or Tailscale)", "", true), field("textfield", "wizard_owner", "Existing owner ID to claim (blank for a new identity)", "", false), map[string]any{"desc": "Copy the following one-time code before continuing. After installation choose First administrator setup in the web client to create your username and password. The code expires 15 minutes after installation. Create Eclipse API tokens under Account after signing in."}, field("textfield", "wizard_setup_code", "One-time administrator setup code", code, true)}}}
	return json.NewEncoder(output).Encode(steps)
}
