package main

import (
	"bytes"
	"errors"
	"io"
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

type pgDiagnostic struct{ value []byte }

func (d *pgDiagnostic) Write(value []byte) (int, error) {
	length := len(value)
	if remaining := 16384 - len(d.value); remaining > 0 {
		if len(value) > remaining {
			value = value[:remaining]
		}
		d.value = append(d.value, value...)
	}
	return length, nil
}

func pgFailure(diagnostic string) error {
	// Match only known diagnostics and return fixed text: SQL errors and role
	// names can contain arbitrary input, including secrets supplied to psql.
	switch {
	case strings.Contains(diagnostic, "password authentication failed for user"), strings.Contains(diagnostic, "no password supplied"):
		return errors.New("PostgreSQL rejected the username or password. Verify the credentials for the selected PostgreSQL account")
	case strings.Contains(diagnostic, "Connection refused"), strings.Contains(diagnostic, "connection to server") && strings.Contains(diagnostic, "timeout expired"):
		return errors.New("Cannot connect to PostgreSQL. Check that the SynoCommunity PostgreSQL package is running and its port is correct (normally 5433)")
	default:
		return errors.New("PostgreSQL operation failed; check the selected package, port and account permissions")
	}
}
func passEscape(value string) string { return strings.NewReplacer(`\`, `\\`, ":", `\:`).Replace(value) }
func pgRun(binary string, port int, user, password, database string, args []string, input io.Reader, output io.Writer) error {
	temporary, err := os.MkdirTemp("", "timekeeper-pg-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temporary)
	path := filepath.Join(temporary, "pgpass")
	line := strings.Join([]string{"127.0.0.1", strconv.Itoa(port), passEscape(database), passEscape(user), passEscape(password)}, ":") + "\n"
	if err = os.WriteFile(path, []byte(line), 0600); err != nil {
		return err
	}
	cmd := exec.Command(binary, args...)
	cmd.Env = pgEnvironment(os.Environ(), "127.0.0.1", port, user, database, path)
	// Keep diagnostic classification independent of the DSM language.
	cmd.Env = append(cmd.Env, "LC_ALL=C")
	cmd.Stdin = input
	cmd.Stdout = output
	var diagnostic pgDiagnostic
	cmd.Stderr = &diagnostic
	// Never relay raw libpq/SQL diagnostics: provisioning input includes credentials.
	if err = cmd.Run(); err != nil {
		return pgFailure(string(diagnostic.value))
	}
	return nil
}
func psql(binary string, port int, user, password, database, sql string) (string, error) {
	var output bytes.Buffer
	err := pgRun(binary, port, user, password, database, []string{"-X", "-q", "-A", "-t", "-w", "-v", "ON_ERROR_STOP=1"}, strings.NewReader(sql), &output)
	return strings.TrimSpace(output.String()), err
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
