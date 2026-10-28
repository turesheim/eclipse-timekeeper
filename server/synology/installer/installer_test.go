package main

import (
	"archive/tar"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	syncatomic "sync/atomic"
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
func TestStateRootSurvivesMissingPackageLinksDuringUpgrade(t *testing.T) {
	for _, item := range []struct{ pkgvar, volume, expected string }{
		{"", "", "/var/packages/timekeeper/var/server"},
		{"/var/packages/timekeeper/var", "/volume2", "/volume2/@appdata/timekeeper/server"},
		{"", "/volume3", "/volume3/@appdata/timekeeper/server"},
		{"/volume4/@appdata/timekeeper", "/volume4", "/volume4/@appdata/timekeeper/server"},
	} {
		root, err := stateRoot(func(key string) string {
			return map[string]string{"SYNOPKG_PKGVAR": item.pkgvar, "SYNOPKG_PKGDEST_VOL": item.volume}[key]
		})
		if err != nil || root != item.expected {
			t.Fatalf("wrong persistent appdata path: %q, %v", root, err)
		}
	}
	for _, invalid := range []string{"relative/path", "/", "/volume1/../volume2"} {
		if _, err := stateRoot(func(key string) string {
			if key == "SYNOPKG_PKGDEST_VOL" {
				return invalid
			}
			return ""
		}); err == nil {
			t.Fatal("accepted an invalid SDK volume location")
		}
	}
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
	for _, value := range []string{"http://nas.local", "https://nas.local/", "https://user:password@nas.local", "https://nas.local/path", "https://nas.local?other=1", "https://nas.local,other.example", "https://*.example", "https://nas.local:99999", "https://nas.local\nother=setting"} {
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

func TestPostgreSQLFailuresExposeUsefulMessagesWithoutRawDiagnostics(t *testing.T) {
	for _, item := range []struct{ diagnostic, expected string }{
		{`psql: error: connection to server failed: FATAL:  password authentication failed for user "private-user"`, "PostgreSQL rejected the username or password"},
		{`psql: error: connection to server failed: fe_sendauth: no password supplied`, "PostgreSQL rejected the username or password"},
		{`psql: error: connection to server failed: Connection refused`, "Cannot connect to PostgreSQL"},
		{`psql: error: connection to server failed: timeout expired`, "Cannot connect to PostgreSQL"},
		{`ERROR: private-user private-password SQL statement`, "PostgreSQL operation failed"},
	} {
		failure := pgFailure(item.diagnostic).Error()
		if !strings.HasPrefix(failure, item.expected) || strings.Contains(failure, "private-") {
			t.Fatal("unsafe or unhelpful PostgreSQL diagnostic")
		}
	}
	var diagnostic pgDiagnostic
	value := bytes.Repeat([]byte("x"), 32768)
	if length, err := diagnostic.Write(value); err != nil || length != len(value) || len(diagnostic.value) != 16384 {
		t.Fatal("PostgreSQL diagnostic capture is not bounded")
	}
}

func TestHTTPPortIsOptionalAndAutomaticSelectionHandlesConflicts(t *testing.T) {
	env := fixtureEnv(fixtureWizard(t))
	w, err := parseWizard(func(key string) string {
		if key == "wizard_http_port" {
			return ""
		}
		return env(key)
	})
	if err != nil || w.Settings.HTTPPort != 0 {
		t.Fatal("wizard requires a manual HTTP port")
	}
	var attempted []string
	port, err := chooseHTTPPort(0, 5433, func(network, address string) (net.Listener, error) {
		attempted = append(attempted, address)
		if address == "127.0.0.1:8080" {
			return nil, errors.New("already in use")
		}
		return net.Listen(network, address)
	})
	if err != nil || port < 1024 || port > 65535 || strings.Join(attempted, ",") != "127.0.0.1:8080,127.0.0.1:0" {
		t.Fatal("automatic port selection did not fall back to a free loopback port")
	}
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal("selected port remained reserved by the installer")
	}
	defer listener.Close()
	if _, err = chooseHTTPPort(port, 5433, net.Listen); err == nil {
		t.Fatal("occupied explicit override was silently changed")
	}
	port, err = chooseHTTPPort(0, 8080, net.Listen)
	if err != nil || port == 8080 {
		t.Fatal("automatic HTTP port reused the database port")
	}
}

func TestInstallerFailureIsWrittenToTheDSMErrorFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "dsm-error")
	failure := errors.New("PostgreSQL rejected the username or password")
	writeInstallerFailure(path, failure)
	value, err := os.ReadFile(path)
	if err != nil || string(value) != failure.Error()+"\n" {
		t.Fatal("DSM error file did not receive the actionable message")
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("DSM error file is not private")
	}
	writeInstallerFailure("", failure) // Command-line maintenance has no DSM file.
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
func TestWizardKeepsTheDisplayedCodeUntilInstallationAndThenRemovesItsTemporaryCopy(t *testing.T) {
	temporary := t.TempDir()
	env := func(key string) string {
		if key == "SYNOPKG_PKGTMP" {
			return temporary
		}
		return ""
	}
	first, err := wizardCode(env)
	if err != nil {
		t.Fatal(err)
	}
	second, err := wizardCode(env)
	if err != nil || first != second {
		t.Fatal("DSM wizard regeneration replaced the displayed code")
	}
	path, _ := wizardCodePath(env)
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("wizard code cache is not private")
	}
	if err := clearWizardCode(env); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("plaintext setup code survived installation")
	}
	third, err := wizardCode(env)
	if err != nil || first == third {
		t.Fatal("new installation reused the previous code")
	}
}
func TestWizardRefusesUnsafeTemporaryCodeCaches(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, ".timekeeper-setup-code")
	outside := filepath.Join(t.TempDir(), "outside")
	if err := os.WriteFile(outside, []byte(strings.Repeat("A", 43)), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, path); err != nil {
		t.Fatal(err)
	}
	if _, err := wizardCode(func(string) string { return directory }); err == nil {
		t.Fatal("followed a wizard cache symlink")
	}
	for _, invalid := range []string{"/", "relative", "/tmp/../etc"} {
		if _, err := wizardCodePath(func(string) string { return invalid }); err == nil {
			t.Fatal("accepted an invalid SDK temporary path")
		}
	}
}
func TestProjectUsesPinnedImageNativeDatabasePrivateFilesAndTheSelectedPort(t *testing.T) {
	w := fixtureWizard(t)
	root, packageDirectory := t.TempDir(), t.TempDir()
	target := filepath.Join(packageDirectory, "target")
	if err := os.Mkdir(target, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(packageDirectory, "INFO"), []byte("package=\"timekeeper\"\nversion=\"1.99.0-146\"\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if spkVersionAt(filepath.Join(packageDirectory, "INFO")) != "1.99.0-146" {
		t.Fatal("installed INFO version could not be read")
	}
	s := w.Settings
	s.Release = Release{Commit: strings.Repeat("a", 40), Image: "ghcr.io/turesheim/eclipse-timekeeper-server:sha-" + strings.Repeat("a", 40), Schema: 2}
	s.Phase = "READY"
	for _, name := range []string{"config", "secrets"} {
		if err := os.Mkdir(filepath.Join(root, name), 0755); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"config/tokens.json", "config/account-setup.json", "secrets/database_password"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("fixture"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	blueprint, err := os.ReadFile("../project/compose.json")
	if err != nil {
		t.Fatal(err)
	}
	original := []byte(strings.ReplaceAll(string(blueprint), "@TIMEKEEPER_IMAGE@", s.Release.Image))
	if err = os.Mkdir(filepath.Join(target, "project"), 0755); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(target, "project/compose.yaml"), original, 0644); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"config", "secrets"} {
		if err := os.MkdirAll(filepath.Join(target, "private", name), 0755); err != nil {
			t.Fatal(err)
		}
	}
	mountedBefore, err := os.Stat(filepath.Join(target, "private/config"))
	if err != nil {
		t.Fatal(err)
	}
	if err := render(root, target, s); err != nil {
		t.Fatal(err)
	}
	runtime, err := os.ReadFile(filepath.Join(target, "private/config/runtime.properties"))
	if err != nil || !strings.Contains(string(runtime), "timekeeper.spk-version=") {
		t.Fatal("SPK version configuration was not supplied to the server")
	}
	mountedAfter, err := os.Stat(filepath.Join(target, "private/config"))
	if err != nil {
		t.Fatal(err)
	}
	private, err := os.Stat(filepath.Join(target, "private"))
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(mountedBefore, mountedAfter) || private.Mode().Perm() != 0700 {
		t.Fatal("postinst replaced a mounted directory or exposed private configuration")
	}
	content, err := os.ReadFile(filepath.Join(target, "project", "compose.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(content, original) {
		t.Fatal("postinst changed a project already acquired by Container Manager")
	}
	properties, err := os.ReadFile(filepath.Join(target, "private/config/runtime.properties"))
	if err != nil {
		t.Fatal(err)
	}
	port, err := os.ReadFile(filepath.Join(target, "private/config/http-port"))
	if err != nil {
		t.Fatal(err)
	}
	if string(port) != "8098\n" {
		t.Fatal("wrong runtime health port")
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
	if server.NetworkMode != "host" || server.Environment["QUARKUS_HTTP_HOST"] != "127.0.0.1" || !strings.Contains(string(properties), "timekeeper.schema.allow-upgrade=false") {
		t.Fatal("unsafe listener or migration configuration")
	}
	if !strings.Contains(string(properties), "127.0.0.1:5433/timekeeper") || !strings.Contains(strings.Join(server.Healthcheck.Test, " "), "/config/http-port") {
		t.Fatal("database or healthcheck uses the wrong port")
	}
	for _, item := range []struct{ origin, host string }{
		{"https://seshat.tailad1f0.ts.net:8443", "seshat.tailad1f0.ts.net"},
		{"https://NAS.EXAMPLE:443", "nas.example"},
		{"https://[2001:db8::1]:8443", "[2001:db8::1]"},
	} {
		s.PublicOrigin = item.origin
		if err := publishConfiguration(root, target, s); err != nil {
			t.Fatal(err)
		}
		properties, err := os.ReadFile(filepath.Join(target, "private/config/runtime.properties"))
		if err != nil || !strings.Contains(string(properties), "quarkus.http.host-validation.require-localhost=false\n") {
			t.Fatal("runtime must use the server central Host policy")
		}
		if strings.Contains(string(properties), "host-validation.allowed-hosts=") || strings.Contains(string(properties), "proxy-address-forwarding=true") {
			t.Fatal("runtime pins a stale hostname or trusts forwarded headers")
		}
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

func TestStartWaitsForContainerWorkerReadiness(t *testing.T) {
	for _, healthy := range []bool{true, false} {
		var calls syncatomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			count := calls.Add(1)
			if r.URL.Path != "/q/health/ready" {
				t.Errorf("wrong readiness path")
			}
			if !healthy || count < 2 {
				w.WriteHeader(503)
				return
			}
			w.WriteHeader(200)
		}))
		_, port, _ := net.SplitHostPort(strings.TrimPrefix(server.URL, "http://"))
		s := fixtureWizard(t).Settings
		s.HTTPPort, _ = strconv.Atoi(port)
		s.InstallationID, _ = secret()
		s.Release.Schema = 2
		s.Phase = "READY"
		root := t.TempDir()
		if err := writeJSON(filepath.Join(root, "settings.json"), s, 0600); err != nil {
			t.Fatal(err)
		}
		err := waitReady(root, 50*time.Millisecond, 5*time.Millisecond)
		server.Close()
		if (err == nil) != healthy || calls.Load() < 2 {
			t.Fatalf("incorrect startup readiness result: healthy=%t, calls=%d", healthy, calls.Load())
		}
	}
}

func TestDagsverkPackageIdentityDoesNotReuseTimekeeperPaths(t *testing.T) {
	previous := packageID
	packageID = "dagsverk"
	defer func() { packageID = previous }()
	root, err := stateRoot(func(key string) string {
		if key == "SYNOPKG_PKGDEST_VOL" {
			return "/volume1"
		}
		return ""
	})
	if err != nil || root != "/volume1/@appdata/dagsverk/server" {
		t.Fatalf("wrong destination state: %s %v", root, err)
	}
	if packageTarget() != "/var/packages/dagsverk/target" || applicationID() != "net.resheim.dagsverk" {
		t.Fatal("destination reused a source package identity")
	}
	if strings.Contains(tailscaleSetupCommand(), "/timekeeper/") {
		t.Fatal("destination instructions address the original package")
	}
	if !strings.Contains(tailscaleSetupCommand(), "/dagsverk/") {
		t.Fatal("missing destination forwarding path")
	}
	env := func(string) string { return "timekeeper" }
	for _, action := range []string{"wizard", "uninstall-wizard", "install", "backup", "restore"} {
		if run(action, root, packageTarget(), t.TempDir(), env, "") == nil {
			t.Fatalf("destination helper accepted a source package action: %s", action)
		}
	}
}
