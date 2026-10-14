package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func render(root, target string, s Settings) error {
	config := filepath.Join(root, "config")
	secretFile := filepath.Join(root, "secrets", "database_password")
	environment := map[string]string{"TIMEKEEPER_SERVER_DB_URL": fmt.Sprintf("jdbc:postgresql://127.0.0.1:%d/%s", s.DatabasePort, s.Database), "TIMEKEEPER_SERVER_DB_USER": s.Role, "TIMEKEEPER_SERVER_DB_PASSWORD_FILE": "/run/secrets/database_password", "TIMEKEEPER_SERVER_TOKENS_FILE": "/config/tokens.json", "TIMEKEEPER_SERVER_SETUP_FILE": "/config/account-setup.json", "TIMEKEEPER_PUBLIC_ORIGIN": s.PublicOrigin, "TIMEKEEPER_DEPLOYED_AT": s.DeployedAt, "TIMEKEEPER_ALLOW_SCHEMA_UPGRADE": "false", "QUARKUS_HTTP_HOST": "127.0.0.1", "QUARKUS_HTTP_PORT": strconv.Itoa(s.HTTPPort)}
	service := map[string]any{"image": s.Release.Image, "network_mode": "host", "restart": "unless-stopped", "read_only": true, "init": true, "tmpfs": []string{"/tmp"}, "cap_drop": []string{"ALL"}, "security_opt": []string{"no-new-privileges:true"}, "environment": environment, "volumes": []any{map[string]any{"type": "bind", "source": config, "target": "/config", "read_only": true}, map[string]any{"type": "bind", "source": secretFile, "target": "/run/secrets/database_password", "read_only": true}}, "logging": map[string]any{"driver": "json-file", "options": map[string]string{"max-size": "10m", "max-file": "3"}}}
	service["healthcheck"] = map[string]any{"test": []string{"CMD", "curl", "--fail", "--silent", fmt.Sprintf("http://127.0.0.1:%d/q/health/ready", s.HTTPPort)}, "interval": "10s", "timeout": "5s", "start_period": "60s", "retries": 3}
	if err := os.MkdirAll(filepath.Join(target, "project"), 0755); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(target, "ui"), 0755); err != nil {
		return err
	}
	if err := writeJSON(filepath.Join(target, "project", "compose.yaml"), map[string]any{"services": map[string]any{"server": service}}, 0644); err != nil {
		return err
	}
	launch := map[string]any{".url": map[string]any{"net.resheim.timekeeper": map[string]any{"type": "url", "title": "Timekeeper", "desc": "Timekeeper web client", "allUsers": true, "icon": "images/timekeeper_{0}.png", "url": s.PublicOrigin + "/web/"}}}
	return writeJSON(filepath.Join(target, "ui", "config"), launch, 0644)
}
func writeChallenge(root string, w Wizard) error {
	digest := sha256.Sum256([]byte(w.Code))
	challenge := map[string]any{"purpose": "SETUP", "sha256": hex.EncodeToString(digest[:]), "expiresAt": time.Now().Add(15 * time.Minute).UTC().Format(time.RFC3339Nano)}
	if w.Settings.Owner != "" {
		challenge["ownerId"] = w.Settings.Owner
	}
	return writeJSON(filepath.Join(root, "config", "account-setup.json"), challenge, 0644)
}
func install(root, target string, w Wizard, r Release) error {
	if info, err := os.Lstat(root); err == nil && (!info.IsDir() || info.Mode()&os.ModeSymlink != 0) {
		return errors.New("Refusing an unexpected state directory")
	}
	retained, err := loadSettings(root)
	if err == nil {
		if retained.Release.Schema != r.Schema {
			return errors.New("A schema migration requires a separate tested maintenance procedure")
		}
		if retained.Phase != "READY" {
			if w.AdminPassword == "" {
				return errors.New("Repair needs the PostgreSQL administrator credentials")
			}
			password, readErr := os.ReadFile(filepath.Join(root, "secrets", "database_password"))
			if readErr != nil {
				return errors.New("Restore the original dedicated database credential")
			}
			w.Settings = retained
			if err = probe(w, nativePSQL, true); err != nil {
				return err
			}
			if err = provision(w, strings.TrimSpace(string(password)), nativePSQL); err != nil {
				return err
			}
			retained.Phase = "READY"
		}
		if w.Code != "" && retained.Phase == "READY" {
			if err = renewSetup(root, retained, w.Code); err != nil {
				return err
			}
		}
		// Reinstallation/upgrade never reset credentials or infer ownership from a name.
		retained.Release = r
		retained.DeployedAt = time.Now().UTC().Format(time.RFC3339Nano)
		if err = render(root, target, retained); err != nil {
			return err
		}
		return writeJSON(filepath.Join(root, "settings.json"), retained, 0600)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return errors.New("Restore the retained Timekeeper configuration before reinstalling")
	}
	if err = os.MkdirAll(root, 0700); err != nil {
		return err
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return err
	}
	if len(entries) != 0 {
		return errors.New("State directory is not empty; existing state will not be overwritten")
	}
	if err = probe(w, nativePSQL, false); err != nil {
		return err
	}
	password, err := secret()
	if err != nil {
		return err
	}
	installation, err := secret()
	if err != nil {
		return err
	}
	s := w.Settings
	s.InstallationID = installation
	s.Release = r
	s.DeployedAt = time.Now().UTC().Format(time.RFC3339Nano)
	s.Phase = "PROVISIONING"
	for _, name := range []string{"config", "secrets", "backups"} {
		mode := os.FileMode(0700)
		if name == "config" {
			mode = 0755
		}
		if err = os.Mkdir(filepath.Join(root, name), mode); err != nil {
			return err
		}
	}
	if err = atomic(filepath.Join(root, "secrets", "database_password"), []byte(password+"\n"), 0444); err != nil {
		return err
	}
	if err = writeChallenge(root, w); err != nil {
		return err
	}
	if err = atomic(filepath.Join(root, "config", "tokens.json"), []byte("[]\n"), 0644); err != nil {
		return err
	}
	if err = writeJSON(filepath.Join(root, "settings.json"), s, 0600); err != nil {
		return err
	}
	w.Settings = s
	if err = provision(w, password, nativePSQL); err != nil {
		return err
	}
	s.Phase = "READY"
	if err = writeJSON(filepath.Join(root, "settings.json"), s, 0600); err != nil {
		return err
	}
	return render(root, target, s)
}

// Reinstall can renew an expired first-setup code, but never reopens a completed
// bootstrap. The package is stopped while the control scripts run.
func renewSetup(root string, s Settings, code string) error {
	password, err := os.ReadFile(filepath.Join(root, "secrets", "database_password"))
	if err != nil {
		return err
	}
	query := func(sql string) (string, error) {
		return psql(nativePSQL, s.DatabasePort, s.Role, strings.TrimSpace(string(password)), s.Database, sql)
	}
	table, err := query("SELECT COALESCE(to_regclass('public.tk_auth_control')::text,'');")
	if err != nil {
		return err
	}
	if table != "" {
		// Serialize with account setup and invalidate earlier pending codes.
		completed, err := query("BEGIN; SELECT bootstrap_completed FROM tk_auth_control WHERE id=1 FOR UPDATE; UPDATE tk_auth_challenge SET consumed=TRUE WHERE purpose='SETUP' AND NOT (SELECT bootstrap_completed FROM tk_auth_control WHERE id=1); COMMIT;")
		if err != nil {
			return err
		}
		if completed == "t" {
			return nil
		}
		if completed != "f" {
			return errors.New("Invalid retained account bootstrap state")
		}
	}
	return writeChallenge(root, Wizard{Settings: s, Code: code})
}
