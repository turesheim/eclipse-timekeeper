// The installer runs as DSM's package user. Privileged container operations
// belong to Container Manager's docker-project resource worker.
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

const packageID = "timekeeper"
const packageTarget = "/var/packages/timekeeper/target"
const nativePSQL = "/var/packages/postgresql/target/bin/psql"
const currentSchema = 7

func supportedSchema(version int) bool { return version >= 2 && version <= currentSchema }
func supportedUpgrade(from, to int) bool {
	return supportedSchema(from) && from < to && to == currentSchema
}

var identifier = regexp.MustCompile(`^[a-z][a-z0-9_]{0,62}$`)
var commitID = regexp.MustCompile(`^[a-f0-9]{40}$`)
var dnsLabel = regexp.MustCompile(`^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$`)

type Release struct {
	Commit string `json:"commit"`
	Image  string `json:"image"`
	Schema int    `json:"serverSchema"`
}
type Settings struct {
	Format         int     `json:"format"`
	InstallationID string  `json:"installationId"`
	Database       string  `json:"database"`
	Role           string  `json:"role"`
	DatabasePort   int     `json:"databasePort"`
	HTTPPort       int     `json:"httpPort"`
	PublicOrigin   string  `json:"publicOrigin"`
	Owner          string  `json:"ownerId,omitempty"`
	DeployedAt     string  `json:"deployedAt"`
	Phase          string  `json:"phase"`
	Release        Release `json:"release"`
	Tailscale      bool    `json:"tailscale,omitempty"`
}
type Wizard struct {
	Settings      Settings
	AdminUser     string
	AdminPassword string
	Code          string
	UpdateAccess  bool
}

func secret() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}
func origin(value string) error {
	u, err := url.Parse(value)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return errors.New("Enter the exact public HTTPS origin without a path or trailing slash")
	}
	// This hostname also becomes a literal entry in Quarkus's Host allowlist.
	// Reject wildcards, list separators and property escapes before rendering it.
	host := u.Hostname()
	if net.ParseIP(host) == nil {
		if len(host) > 253 {
			return errors.New("Enter a valid public HTTPS hostname")
		}
		for _, label := range strings.Split(strings.TrimSuffix(host, "."), ".") {
			if !dnsLabel.MatchString(label) {
				return errors.New("Enter a valid public HTTPS hostname")
			}
		}
	}
	if u.Port() != "" {
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return errors.New("Enter a valid public HTTPS port")
		}
	}
	return nil
}
func validInstallationID(value string) bool {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return err == nil && len(decoded) == 32 && base64.RawURLEncoding.EncodeToString(decoded) == value
}
func validIdentity(value string) bool {
	return identifier.MatchString(value) && !strings.HasPrefix(value, "pg_") && value != "postgres" && value != "sc_postgresql"
}
func parseWizard(env func(string) string) (Wizard, error) {
	number := func(key string) (int, error) {
		value, err := strconv.Atoi(env(key))
		if err != nil || value < 1024 || value > 65535 {
			return 0, errors.New("Use ports between 1024 and 65535")
		}
		return value, nil
	}
	var w Wizard
	var err error
	w.Settings = Settings{Format: 1, Database: env("wizard_database"), Role: env("wizard_database_role"), PublicOrigin: env("wizard_public_origin"), Owner: env("wizard_owner")}
	if !validIdentity(w.Settings.Database) || !validIdentity(w.Settings.Role) {
		return w, errors.New("Choose dedicated lowercase database and role names; system names are reserved")
	}
	if w.Settings.DatabasePort, err = number("wizard_database_port"); err != nil {
		return w, err
	}
	if w.Settings.DatabasePort == 5432 {
		return w, errors.New("Use the SynoCommunity PostgreSQL port, normally 5433; DSM's built-in database is not supported")
	}
	if strings.TrimSpace(env("wizard_http_port")) != "" {
		if w.Settings.HTTPPort, err = number("wizard_http_port"); err != nil {
			return w, err
		}
	}
	if w.Settings.HTTPPort == w.Settings.DatabasePort {
		return w, errors.New("The HTTP and database ports must differ")
	}
	w.Settings, err = configureAccess(w.Settings, env)
	if err != nil {
		return w, err
	}
	if err = origin(w.Settings.PublicOrigin); err != nil {
		return w, err
	}
	if w.Settings.Owner == "local" || strings.TrimSpace(w.Settings.Owner) != w.Settings.Owner || len([]rune(w.Settings.Owner)) > 200 {
		return w, errors.New("Choose an explicit stable owner ID or leave it blank for a new identity")
	}
	w.AdminUser = env("wizard_database_admin")
	w.AdminPassword = env("wizard_database_admin_password")
	w.Code = env("wizard_setup_code")
	if w.AdminUser == "" || len(w.AdminUser) > 200 || strings.ContainsAny(w.AdminUser, "\x00\r\n") || w.AdminPassword == "" || len(w.AdminPassword) > 1024 || strings.ContainsAny(w.AdminPassword, "\x00\r\n") {
		return w, errors.New("Check the PostgreSQL administrator credentials")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(w.Code)
	if err != nil || len(decoded) != 32 || base64.RawURLEncoding.EncodeToString(decoded) != w.Code {
		return w, errors.New("Reopen the installer to generate a new one-time code")
	}
	return w, nil
}
func readJSON(path string, target any) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	d := json.NewDecoder(io.LimitReader(f, 16384))
	d.DisallowUnknownFields()
	if err = d.Decode(target); err != nil {
		return err
	}
	if d.Decode(new(any)) != io.EOF {
		return errors.New("Unexpected configuration content")
	}
	return nil
}
func releaseAt(target string) (Release, error) {
	var r Release
	err := readJSON(filepath.Join(target, "release.json"), &r)
	if err != nil || !commitID.MatchString(r.Commit) || r.Image != "ghcr.io/turesheim/eclipse-timekeeper-server:sha-"+r.Commit || !supportedSchema(r.Schema) {
		return r, errors.New("Invalid or incompatible package release identity")
	}
	return r, nil
}
func atomic(path string, value []byte, mode os.FileMode) error {
	if info, err := os.Lstat(path); err == nil && info.Mode()&os.ModeSymlink != 0 {
		return errors.New("Refusing a configuration symlink")
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".timekeeper-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(mode); err == nil {
		_, err = f.Write(value)
	}
	if err == nil {
		err = f.Sync()
	}
	if closeErr := f.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(name, path)
}
func writeJSON(path string, value any, mode os.FileMode) error {
	content, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return atomic(path, append(content, '\n'), mode)
}

// DSM can remove /var/packages/<name>/var between preupgrade and preinst.
// Resolve permanent appdata from SDK paths without relying on that transient
// link. Never scan other volumes or create state during validation.
func stateRoot(env func(string) string) (string, error) {
	alias := "/var/packages/" + packageID + "/var"
	pkgvar, volume := env("SYNOPKG_PKGVAR"), env("SYNOPKG_PKGDEST_VOL")
	for _, path := range []string{pkgvar, volume} {
		if path != "" && (!filepath.IsAbs(path) || filepath.Clean(path) != path || path == "/") {
			return "", errors.New("Invalid DSM permanent appdata location")
		}
	}
	if pkgvar != "" && pkgvar != alias {
		return filepath.Join(pkgvar, "server"), nil
	}
	if volume != "" {
		return filepath.Join(volume, "@appdata", packageID, "server"), nil
	}
	return filepath.Join(alias, "server"), nil
}
func loadSettings(root string) (Settings, error) {
	var s Settings
	err := readJSON(filepath.Join(root, "settings.json"), &s)
	if err != nil {
		return s, err
	}
	if s.Format != 1 || !validIdentity(s.Database) || !validIdentity(s.Role) || !validInstallationID(s.InstallationID) || origin(s.PublicOrigin) != nil || !supportedSchema(s.Release.Schema) || (s.Phase != "READY" && s.Phase != "PROVISIONING") {
		return s, errors.New("Invalid retained Timekeeper configuration")
	}
	return s, nil
}
