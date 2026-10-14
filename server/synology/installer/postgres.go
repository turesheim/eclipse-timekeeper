package main

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

func pgEnvironment(values []string, host string, port int, user, database, passfile string) []string {
	out := []string{}
	for _, entry := range values {
		key := strings.SplitN(entry, "=", 2)[0]
		if !strings.HasPrefix(key, "PG") && !strings.HasPrefix(key, "wizard_") {
			out = append(out, entry)
		}
	}
	return append(out, "PGHOST="+host, "PGPORT="+strconv.Itoa(port), "PGUSER="+user, "PGDATABASE="+database, "PGPASSFILE="+passfile, "PGCONNECT_TIMEOUT=5", "PGSSLMODE=disable")
}
func passEscape(value string) string { return strings.NewReplacer(`\`, `\\`, ":", `\:`).Replace(value) }
func psql(binary string, port int, user, password, database, sql string) (string, error) {
	temporary, err := os.MkdirTemp("", "timekeeper-pg-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(temporary)
	path := filepath.Join(temporary, "pgpass")
	line := strings.Join([]string{"127.0.0.1", strconv.Itoa(port), passEscape(database), passEscape(user), passEscape(password)}, ":") + "\n"
	if err = os.WriteFile(path, []byte(line), 0600); err != nil {
		return "", err
	}
	cmd := exec.Command(binary, "-X", "-q", "-A", "-t", "-w", "-v", "ON_ERROR_STOP=1")
	cmd.Env = pgEnvironment(os.Environ(), "127.0.0.1", port, user, database, path)
	cmd.Stdin = strings.NewReader(sql)
	var output bytes.Buffer
	cmd.Stdout = &output
	// Never relay libpq/SQL diagnostics: provisioning input includes credentials.
	if err = cmd.Run(); err != nil {
		return "", errors.New("PostgreSQL operation failed; check the selected package, port and administrator credentials")
	}
	return strings.TrimSpace(output.String()), nil
}
func probe(w Wizard, binary string, allowExisting bool) error {
	value, err := psql(binary, w.Settings.DatabasePort, w.AdminUser, w.AdminPassword, "postgres", "SELECT current_setting('server_version_num');\n")
	if err != nil {
		return err
	}
	version, err := strconv.Atoi(value)
	if err != nil || version < 170000 || version >= 180000 {
		return errors.New("This package supports SynoCommunity PostgreSQL 17; do not select DSM's built-in PostgreSQL")
	}
	if allowExisting {
		return nil
	}
	value, err = psql(binary, w.Settings.DatabasePort, w.AdminUser, w.AdminPassword, "postgres", "SELECT COUNT(*) FROM pg_roles WHERE rolname='"+w.Settings.Role+"'; SELECT COUNT(*) FROM pg_database WHERE datname='"+w.Settings.Database+"';\n")
	if err != nil {
		return err
	}
	if value != "0\n0" {
		return errors.New("That database or role already exists; use retained Timekeeper settings or choose unused dedicated names")
	}
	return nil
}
func provision(w Wizard, password, binary string) error {
	if !validInstallationID(w.Settings.InstallationID) {
		return errors.New("Invalid provisioning identity")
	}
	marker := "timekeeper:" + w.Settings.InstallationID
	inspect := func(sql string) (string, error) {
		return psql(binary, w.Settings.DatabasePort, w.AdminUser, w.AdminPassword, "postgres", sql)
	}
	role, err := inspect("SELECT COALESCE((SELECT concat_ws('|',rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,COALESCE(shobj_description(oid,'pg_authid'),'')) FROM pg_roles WHERE rolname='" + w.Settings.Role + "'),'MISSING');")
	if err != nil {
		return err
	}
	create := ""
	if role == "MISSING" {
		create = "CREATE ROLE " + w.Settings.Role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"
	} else if role != "t|f|f|f|f|f|"+marker {
		return errors.New("The existing database role does not belong to this installation")
	}
	// psql hashes on the client. Passwords never enter SQL or process arguments.
	roleSQL := "BEGIN; SET password_encryption='scram-sha-256'; " + create + "\n\\password " + w.Settings.Role + "\n" + password + "\n" + password + "\nCOMMENT ON ROLE " + w.Settings.Role + " IS '" + marker + "'; COMMIT;\n"
	if _, err = inspect(roleSQL); err != nil {
		return err
	}
	database, err := inspect("SELECT COALESCE((SELECT pg_get_userbyid(datdba)||'|'||COALESCE(shobj_description(oid,'pg_database'),'') FROM pg_database WHERE datname='" + w.Settings.Database + "'),'MISSING');")
	if err != nil {
		return err
	}
	if database == "MISSING" {
		// template0 excludes SynoCommunity's template1 extension objects.
		if _, err = inspect("CREATE DATABASE " + w.Settings.Database + " OWNER " + w.Settings.Role + " TEMPLATE template0 ENCODING 'UTF8';"); err != nil {
			return err
		}
	} else if database != w.Settings.Role+"|"+marker && database != w.Settings.Role+"|" {
		return errors.New("The existing database does not belong to this installation")
	}
	// Lost acknowledgements are resumable; the owning role carries our marker.
	_, err = inspect("BEGIN; REVOKE ALL ON DATABASE " + w.Settings.Database + " FROM PUBLIC; COMMENT ON DATABASE " + w.Settings.Database + " IS '" + marker + "'; COMMIT;")
	return err
}
