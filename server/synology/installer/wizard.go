package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
)

// DSM may ask for the same wizard more than once. Keep its displayed code stable
// for that install attempt, in SDK temporary storage only. Postinst/postupgrade
// remove the plaintext cache after storing the digest, never in retained appdata.
func wizardCodePath(env func(string) string) (string, error) {
	temporary := env("SYNOPKG_PKGTMP")
	if temporary == "" {
		return "", nil
	}
	if !filepath.IsAbs(temporary) || filepath.Clean(temporary) != temporary || temporary == "/" {
		return "", errors.New("Invalid DSM wizard temporary directory")
	}
	return filepath.Join(temporary, ".timekeeper-setup-code"), nil
}
func wizardCode(env func(string) string) (string, error) {
	path, err := wizardCodePath(env)
	if err != nil {
		return "", err
	}
	if path == "" {
		return secret()
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if errors.Is(err, os.ErrExist) {
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() != 43 {
			return "", errors.New("Invalid DSM setup-code cache; reopen the installer")
		}
		data, err := os.ReadFile(path)
		if err != nil || !validInstallationID(string(data)) {
			return "", errors.New("Cannot read the DSM setup-code cache; reopen the installer")
		}
		return string(data), nil
	}
	if err != nil {
		return "", err
	}
	complete := false
	defer func() {
		file.Close()
		if !complete {
			_ = os.Remove(path)
		}
	}()
	code, err := secret()
	if err != nil {
		return "", err
	}
	if _, err = file.WriteString(code); err != nil {
		return "", err
	}
	if err = file.Sync(); err != nil {
		return "", err
	}
	complete = true
	return code, nil
}
func clearWizardCode(env func(string) string) error {
	path, err := wizardCodePath(env)
	if err != nil || path == "" {
		return err
	}
	if err = os.Remove(path); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func field(kind, key, label, value string, required bool) map[string]any {
	// DSM renders the group description and the individual field label separately.
	// A single-field group needs only the field label.
	return map[string]any{"type": kind, "desc": "", "subitems": []any{map[string]any{"key": key, "desc": label, "defaultValue": value, "validator": map[string]any{"allowBlank": !required}}}}
}
func wizard(output io.Writer, root string) error {
	code, err := wizardCode(os.Getenv)
	if err != nil {
		return err
	}
	if s, err := loadSettings(root); err == nil && s.Phase == "READY" {
		return json.NewEncoder(output).Encode([]any{map[string]any{"step_title": "Retained Timekeeper installation", "items": []any{
			map[string]any{"desc": "The retained database, application credential, owner mapping and local port will be reused. Web access can be changed on the next page. No PostgreSQL administrator password is required. If first administrator setup was never completed, reinstalling renews its one-time code. Completed accounts are never reset."},
			field("textfield", "wizard_setup_code", "Copy this code only if first administrator setup is still pending", code, true),
		}}, map[string]any{"step_title": "Web access", "items": tailscaleFields(s)}})
	}
	steps := []any{map[string]any{"step_title": "Timekeeper server", "items": []any{map[string]any{"desc": "Install SynoCommunity PostgreSQL and Container Manager first. Administrator credentials are used only to create a dedicated non-superuser database role. Private settings, backups and the database are retained when this package is removed. DSM places the settings on the selected package volume."}, field("textfield", "wizard_database_admin", "PostgreSQL administrator", "pgadmin", true), field("password", "wizard_database_admin_password", "PostgreSQL administrator password", "", true), field("textfield", "wizard_database_port", "SynoCommunity PostgreSQL port", "5433", true), field("textfield", "wizard_database", "Dedicated database name", "timekeeper", true), field("textfield", "wizard_database_role", "Dedicated application role", "timekeeper_app", true)}}, map[string]any{"step_title": "Web access and first account", "items": append(tailscaleFields(Settings{}), field("textfield", "wizard_owner", "Existing owner ID to claim (blank for a new identity)", "", false), map[string]any{"desc": "Copy the following one-time code before continuing. After installation choose First administrator setup in the web client to create your username and password. The code expires 15 minutes after installation. Create Eclipse API tokens under Account after signing in."}, field("textfield", "wizard_setup_code", "One-time administrator setup code", code, true))}, map[string]any{"step_title": "Advanced settings (optional)", "items": []any{map[string]any{"desc": "Timekeeper automatically selects a free local HTTP port and retains it across upgrades and restarts. Leave the override empty for automatic selection."}, field("textfield", "wizard_http_port", "Local HTTP port override (optional)", "", false)}}}
	return json.NewEncoder(output).Encode(steps)
}
