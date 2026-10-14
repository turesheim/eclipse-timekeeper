package main

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func validate(root string, w Wizard, r Release) error {
	if retained, err := loadSettings(root); err == nil {
		if retained.Release.Schema != r.Schema {
			return errors.New("Upgrade requires an explicit schema migration; the existing installation is unchanged")
		}
		if retained.Phase != "READY" {
			w.Settings = retained
			return probe(w, nativePSQL, true)
		}
		password, err := os.ReadFile(filepath.Join(root, "secrets", "database_password"))
		if err != nil {
			return errors.New("Restore the original database credential before reinstalling")
		}
		_, err = psql(nativePSQL, retained.DatabasePort, retained.Role, strings.TrimSpace(string(password)), retained.Database, "SELECT 1;")
		return err
	} else if !errors.Is(err, os.ErrNotExist) {
		return errors.New("Retained Timekeeper settings are invalid and will not be replaced")
	}
	if err := probe(w, nativePSQL, false); err != nil {
		return err
	}
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", w.Settings.HTTPPort))
	if listener != nil {
		listener.Close()
	}
	if err != nil {
		return errors.New("The selected local HTTP port is in use")
	}
	return nil
}
func ready(root string) error {
	s, err := loadSettings(root)
	if err != nil {
		return err
	}
	if s.Phase != "READY" {
		return errors.New("Installation is incomplete")
	}
	client := http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/q/health/ready", s.HTTPPort))
	if err != nil {
		return errors.New("Timekeeper is not ready")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return errors.New("Timekeeper is not ready")
	}
	return nil
}
func run(action, root, target, releasePath string, env func(string) string, argument string) error {
	if action == "wizard" {
		return wizard(os.Stdout, root)
	}
	if env("SYNOPKG_PKGNAME") != packageID {
		return errors.New("Run this helper through DSM Package Center")
	}
	switch action {
	case "backup":
		path, err := backup(root)
		if err == nil {
			fmt.Fprintln(os.Stdout, path)
		}
		return err
	case "cancel-empty-import":
		return cancelEmptyImport(root)
	case "import-postgresql18":
		if !filepath.IsAbs(argument) {
			return errors.New("Provide the absolute migration archive path")
		}
		return importPostgreSQL18(root, argument, filepath.Join(releasePath, "migration"))
	case "restore":
		if !filepath.IsAbs(argument) {
			return errors.New("Provide the absolute path to a verified native PostgreSQL 17 backup")
		}
		return restore(root, argument)
	case "stop", "start":
		return nil // Container Manager's worker owns the lifecycle.
	case "status":
		return ready(root)
	case "validate", "install", "upgrade":
		// preupgrade may run the installed controller against an incoming SPK.
		if incoming := env("SYNOPKG_PKGINST_TEMP_DIR"); incoming != "" {
			releasePath = filepath.Join(incoming, "scripts")
		}
		release, err := releaseAt(releasePath)
		if err != nil {
			return err
		}
		var w Wizard
		if retained, loadErr := loadSettings(root); loadErr != nil || retained.Phase != "READY" {
			w, err = parseWizard(env)
			if err != nil {
				return err
			}
		}
		if action == "install" && w.Code == "" && env("wizard_setup_code") != "" {
			w.Code = env("wizard_setup_code")
			if !validInstallationID(w.Code) {
				return errors.New("Reopen the installer to generate a new one-time code")
			}
		}
		if action == "validate" {
			if err := containerManagerAt("/var/packages/ContainerManager/INFO"); err != nil {
				return err
			}
			return validate(root, w, release)
		}
		return install(root, target, w, release)
	default:
		return errors.New("Unknown package action")
	}
}
func main() {
	if len(os.Args) < 2 || len(os.Args) > 3 || (len(os.Args) == 3 && os.Args[1] != "restore" && os.Args[1] != "import-postgresql18") {
		fmt.Fprintln(os.Stderr, "Use wizard, validate, install, upgrade, start, stop, status, backup, restore /absolute/backup.tar, import-postgresql18 /absolute/migration.tar or cancel-empty-import")
		os.Exit(2)
	}
	executable, err := os.Executable()
	if err == nil {
		argument := ""
		if len(os.Args) == 3 {
			argument = os.Args[2]
		}
		err = run(os.Args[1], stateRoot(), os.Getenv("SYNOPKG_PKGDEST"), filepath.Dir(executable), os.Getenv, argument)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
