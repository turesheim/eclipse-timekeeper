package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func render(root, target string, s Settings) error {
	if err := publishConfiguration(root, target, s); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(target, "ui"), 0755); err != nil {
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
func install(root, target string, w Wizard, r Release, resources string) error {
	if info, err := os.Lstat(root); err == nil && (!info.IsDir() || info.Mode()&os.ModeSymlink != 0) {
		return errors.New("Refusing an unexpected state directory")
	}
	retained, err := loadSettings(root)
	if err == nil {
		_, finishingUpgrade, err := pendingSchemaUpgrade(root)
		if err != nil {
			return err
		}
		if retained.Release.Schema != r.Schema {
			if !supportedUpgrade(retained.Release.Schema, r.Schema) {
				return errors.New("Unsupported Timekeeper schema migration")
			}
			finishingUpgrade = true
		}
		if finishingUpgrade {
			if r.Schema != currentSchema {
				return errors.New("Complete the pending upgrade with the matching package")
			}
			if err := prepareSchemaUpgrade(root, resources); err != nil {
				return err
			}
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
		if w.UpdateAccess {
			retained.Tailscale = w.Settings.Tailscale
			retained.PublicOrigin = w.Settings.PublicOrigin
		}
		retained.Release = r
		retained.DeployedAt = time.Now().UTC().Format(time.RFC3339Nano)
		if err = render(root, target, retained); err != nil {
			return err
		}
		if err := writeJSON(filepath.Join(root, "settings.json"), retained, 0600); err != nil {
			return err
		}
		if finishingUpgrade {
			return finishMaintenance(root, retained)
		}
		return nil
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
	w.Settings.HTTPPort, err = chooseServerPort(w.Settings)
	if err != nil {
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

// Container Manager acquires the project BEFORE postinst. Compose and its bind
// directories therefore ship in package.tgz. postinst writes only files inside
// those existing directories; it never replaces a directory mounted by Docker.
// The parent is private to the package account while each mounted directory is
// readable by the server's distinct container UID.
func publishConfiguration(root, target string, s Settings) error {
	if err := origin(s.PublicOrigin); err != nil {
		return err
	}
	if err := ensureMailConfiguration(root); err != nil {
		return err
	}
	_, err := url.Parse(s.PublicOrigin)
	if err != nil {
		return err
	}
	private := filepath.Join(target, "private")
	if err := os.MkdirAll(private, 0700); err != nil {
		return err
	}
	if err := os.Chmod(private, 0700); err != nil {
		return err
	}
	for _, name := range []string{"config", "secrets"} {
		if err := os.MkdirAll(filepath.Join(private, name), 0755); err != nil {
			return err
		}
	}
	for _, name := range []string{"config/tokens.json", "config/account-setup.json", "config/mail.properties", "secrets/database_password", "secrets/smtp_password"} {
		content, err := os.ReadFile(filepath.Join(root, name))
		if err != nil {
			return err
		}
		mode := os.FileMode(0644)
		if strings.HasPrefix(name, "secrets/") {
			mode = 0444
		}
		if err = atomic(filepath.Join(private, name), content, mode); err != nil {
			return err
		}
	}
	// The server's HostPolicy follows the effective central hostname. A static
	// deployment allowlist would prevent a hostname saved in Administration.
	properties := fmt.Sprintf("quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:%d/%s\nquarkus.datasource.username=%s\nquarkus.http.port=%d\nquarkus.http.host-validation.require-localhost=false\ntimekeeper.auth.public-origin=%s\ntimekeeper.deployed-at=%s\ntimekeeper.schema.allow-upgrade=false\n", s.DatabasePort, s.Database, s.Role, s.HTTPPort, s.PublicOrigin, s.DeployedAt)
	// SYNOPKG_PKGDEST can be /volumeN/@appstore/timekeeper, so its parent is
	// not the package metadata directory. DSM always keeps INFO at this alias.
	properties += "timekeeper.spk-version=" + spkVersionAt("/var/packages/"+packageID+"/INFO") + "\n"
	if err := atomic(filepath.Join(private, "config/runtime.properties"), []byte(properties), 0644); err != nil {
		return err
	}
	if err := atomic(filepath.Join(private, "config/http-port"), []byte(fmt.Sprint(s.HTTPPort)+"\n"), 0644); err != nil {
		return err
	}
	if err := publishTailscaleInstructions(root, private, s); err != nil {
		return err
	}
	return syncMaintenanceMarker(root, target)
}
func syncMaintenanceMarker(root, target string) error {
	marker := "config/maintenance-pending.json"
	content, err := os.ReadFile(filepath.Join(root, marker))
	if errors.Is(err, os.ErrNotExist) {
		err = os.Remove(filepath.Join(target, "private", marker))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	if err != nil {
		return err
	}
	return atomic(filepath.Join(target, "private", marker), content, 0644)
}
func finishMaintenance(root string, s Settings) error {
	if err := publishConfiguration(root, packageTarget, s); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(root, "config/maintenance-pending.json")); err != nil {
		return err
	}
	return syncMaintenanceMarker(root, packageTarget)
}
