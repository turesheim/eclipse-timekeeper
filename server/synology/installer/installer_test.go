package main

import (
	"archive/tar"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fixtureWizard(t *testing.T) Wizard {
	t.Helper()
	code, err := secret()
	if err != nil {
		t.Fatal(err)
	}
	return Wizard{Settings: Settings{Format: 1, Database: "timekeeper", Role: "timekeeper_app", DatabasePort: 5433, HTTPPort: 8098, PublicOrigin: "https://timekeeper.example.ts.net"}, AdminUser: "pgadmin", AdminPassword: "private administrator fixture", Code: code}
}
func fixtureEnv(w Wizard) func(string) string {
	values := map[string]string{"wizard_database": w.Settings.Database, "wizard_database_role": w.Settings.Role, "wizard_database_port": "5433", "wizard_http_port": "8098", "wizard_public_origin": w.Settings.PublicOrigin, "wizard_owner": w.Settings.Owner, "wizard_database_admin": w.AdminUser, "wizard_database_admin_password": w.AdminPassword, "wizard_setup_code": w.Code}
	return func(key string) string { return values[key] }
}
func TestWizardRejectsSystemDatabasesRemoteHttpAndInjectedIdentifiers(t *testing.T) {
	w := fixtureWizard(t)
	if _, err := parseWizard(fixtureEnv(w)); err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"postgres", "pg_database", "a; DROP DATABASE postgres", "some-database", ""} {
		changed := w
		changed.Settings.Database = value
		if _, err := parseWizard(fixtureEnv(changed)); err == nil {
			t.Fatalf("accepted identifier %q", value)
		}
	}
	for _, value := range []string{"http://nas.local", "https://nas.local/", "https://user:password@nas.local", "https://nas.local/path", "https://nas.local?other=1"} {
		changed := w
		changed.Settings.PublicOrigin = value
		if _, err := parseWizard(fixtureEnv(changed)); err == nil {
			t.Fatalf("accepted origin %q", value)
		}
	}
	env := fixtureEnv(w)
	if _, err := parseWizard(func(key string) string {
		if key == "wizard_database_port" {
			return "5432"
		}
		return env(key)
	}); err == nil {
		t.Fatal("accepted DSM system PostgreSQL port")
	}
	changed := w
	changed.Settings.Owner = "local"
	if _, err := parseWizard(fixtureEnv(changed)); err == nil {
		t.Fatal("accepted embedded owner")
	}
}
func TestNativeClientDoesNotInheritAdminCredentialsOrArbitraryPgSettings(t *testing.T) {
	values := pgEnvironment([]string{"PATH=/native/bin", "LANG=en_US.UTF-8", "PGHOST=remote", "PGOPTIONS=unsafe", "PGPASSWORD=master-secret", "wizard_database_admin_password=master-secret"}, "127.0.0.1", 5433, "timekeeper_app", "timekeeper", "/private/passfile")
	result := strings.Join(values, "\n")
	if strings.Contains(result, "master-secret") || strings.Contains(result, "remote") || strings.Contains(result, "unsafe") {
		t.Fatal("credential or inherited libpq setting escaped")
	}
	if !strings.Contains(result, "PGPASSFILE=/private/passfile") || !strings.Contains(result, "PATH=/native/bin") {
		t.Fatal("missing controlled connection environment")
	}
	if passEscape(`a:b\c`) != `a\:b\\c` {
		t.Fatal("invalid libpq password escaping")
	}
}
func TestOneTimeWizardCodeIsRandomAndOnlyItsHashIsRetained(t *testing.T) {
	var first, second bytes.Buffer
	if err := wizard(&first, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if err := wizard(&second, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(first.Bytes(), second.Bytes()) {
		t.Fatal("wizard reused a setup code")
	}
	var steps []map[string]any
	if err := json.Unmarshal(first.Bytes(), &steps); err != nil {
		t.Fatal(err)
	}
	w := fixtureWizard(t)
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "config"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := writeChallenge(root, w); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(root, "config", "account-setup.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(content, []byte(w.Code)) || bytes.Contains(content, []byte(w.AdminPassword)) {
		t.Fatal("retained a raw setup secret")
	}
	var challenge struct {
		Purpose   string
		SHA256    string
		ExpiresAt string
		OwnerID   *string
	}
	if err = json.Unmarshal(content, &challenge); err != nil {
		t.Fatal(err)
	}
	if challenge.Purpose != "SETUP" || len(challenge.SHA256) != 64 || challenge.OwnerID != nil {
		t.Fatal("unbound new setup selected an existing owner")
	}
	expires, err := time.Parse(time.RFC3339Nano, challenge.ExpiresAt)
	if err != nil || time.Until(expires) < 14*time.Minute || time.Until(expires) > 16*time.Minute {
		t.Fatal("invalid challenge lifetime")
	}
}
func TestProjectUsesPinnedImageNativeDatabasePrivateFilesAndTheSelectedPort(t *testing.T) {
	w := fixtureWizard(t)
	root, target := t.TempDir(), t.TempDir()
	s := w.Settings
	s.Release = Release{Commit: strings.Repeat("a", 40), Image: "ghcr.io/turesheim/eclipse-timekeeper-server:sha-" + strings.Repeat("a", 40), Schema: 2}
	s.Phase = "READY"
	if err := render(root, target, s); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(target, "project", "compose.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(content, []byte(w.AdminPassword)) || bytes.Contains(content, []byte(w.Code)) || bytes.Contains(content, []byte("latest")) {
		t.Fatal("project exposes credentials or follows a moving image")
	}
	var project struct {
		Services map[string]struct {
			Image       string
			NetworkMode string `json:"network_mode"`
			Environment map[string]string
			Healthcheck struct{ Test []string }
		}
	}
	if err = json.Unmarshal(content, &project); err != nil {
		t.Fatal(err)
	}
	if len(project.Services) != 1 {
		t.Fatal("DSM package must reuse native PostgreSQL")
	}
	server := project.Services["server"]
	if server.NetworkMode != "host" || server.Environment["QUARKUS_HTTP_HOST"] != "127.0.0.1" || server.Environment["TIMEKEEPER_ALLOW_SCHEMA_UPGRADE"] != "false" {
		t.Fatal("unsafe listener or migration configuration")
	}
	if !strings.Contains(server.Environment["TIMEKEEPER_SERVER_DB_URL"], "127.0.0.1:5433/timekeeper") || !strings.Contains(strings.Join(server.Healthcheck.Test, " "), ":8098/") {
		t.Fatal("database or healthcheck uses the wrong port")
	}
}
func TestAtomicConfigurationRefusesSymlinksAndMalformedRetainedState(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "private")
	if err := os.WriteFile(target, []byte("untouched"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "settings.json")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := atomic(link, []byte("changed"), 0600); err == nil {
		t.Fatal("followed a configuration symlink")
	}
	content, _ := os.ReadFile(target)
	if string(content) != "untouched" {
		t.Fatal("changed unrelated data")
	}
	if _, err := loadSettings(dir); err == nil {
		t.Fatal("accepted malformed retained settings")
	}
}

func TestContainerManagerWorkerMustBeAvailable(t *testing.T) {
	path := filepath.Join(t.TempDir(), "INFO")
	for _, test := range []struct {
		version   string
		supported bool
	}{
		{`version="24.0.2-1432"`, true}, {`version="24.0.2-1630"`, true},
		{`version="24.0.2-1431"`, false}, {`version="unknown"`, false},
	} {
		if err := os.WriteFile(path, []byte(test.version+"\n"), 0600); err != nil {
			t.Fatal(err)
		}
		if got := containerManagerAt(path) == nil; got != test.supported {
			t.Fatalf("incorrect dependency result: %s", test.version)
		}
	}
}

func TestRetainedWizardUsesExistingInstallationWithoutMasterPassword(t *testing.T) {
	root := t.TempDir()
	w := fixtureWizard(t)
	s := w.Settings
	s.InstallationID, _ = secret()
	s.Phase = "READY"
	s.Release.Schema = 2
	if err := writeJSON(filepath.Join(root, "settings.json"), s, 0600); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := wizard(&output, root); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(output.String(), `"key":"wizard_database_admin_password"`) || !strings.Contains(output.String(), `"key":"wizard_setup_code"`) {
		t.Fatal("retained wizard asks for new provisioning")
	}
}

func TestRestoreRefusesUnrecognizedArchivePaths(t *testing.T) {
	for _, name := range []string{"../outside", "/outside", "unexpected"} {
		path := filepath.Join(t.TempDir(), "invalid.tar")
		f, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		writer := tar.NewWriter(f)
		if err = writer.WriteHeader(&tar.Header{Name: name, Mode: 0600, Size: 0}); err != nil {
			t.Fatal(err)
		}
		writer.Close()
		f.Close()
		if _, err = unpackBackup(path, t.TempDir()); err == nil {
			t.Fatalf("accepted member %q", name)
		}
	}
}
