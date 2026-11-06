package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"syscall"
)

const applicationArchive = "application-backup.tar"

// Hyper Backup owns the transport, retention and optional encryption. Its root
// callbacks only manage package lifecycle and pass file descriptors. Archive
// creation and parsing always run as the package account, without Docker access.
type hyperBackupRuntime struct {
	command  func(...string) ([]byte, error)
	transfer func(string, io.Reader, io.Writer) error
}

func hyperBackup(action, input, root, scripts string) error {
	if os.Geteuid() != 0 {
		return errors.New("Run application backup and restore through DSM Hyper Backup as an administrator")
	}
	lock, err := os.OpenFile("/run/"+packageID+"-hyper-backup.lock", os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return errors.New("Cannot acquire the application backup lock")
	}
	defer lock.Close()
	info, err := lock.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Sys().(*syscall.Stat_t).Uid != 0 || info.Sys().(*syscall.Stat_t).Nlink != 1 || info.Mode().Perm() != 0600 {
		return errors.New("Invalid application backup lock")
	}
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return errors.New("Another application backup or restore is running")
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	account, err := user.Lookup(packageID)
	if err != nil {
		return errors.New("Cannot find the installed package account")
	}
	uid, first := strconv.ParseUint(account.Uid, 10, 32)
	gid, second := strconv.ParseUint(account.Gid, 10, 32)
	if first != nil || second != nil || uid == 0 || gid == 0 {
		return errors.New("Invalid package account")
	}
	canonical, err := filepath.EvalSymlinks(root)
	if err != nil {
		return errors.New("Cannot locate the installed package data")
	}
	runtime := hyperBackupRuntime{
		command: func(args ...string) ([]byte, error) {
			cmd := exec.Command("/usr/syno/bin/synopkg", args...)
			cmd.Env = []string{"PATH=/usr/bin:/bin", "LANG=C"}
			return cmd.Output()
		},
		transfer: func(operation string, input io.Reader, output io.Writer) error {
			cmd := exec.Command(filepath.Join(scripts, "timekeeper-dsm"), operation)
			cmd.Dir = "/"
			cmd.Env = []string{"PATH=/usr/bin:/bin", "LANG=C", "SYNOPKG_PKGNAME=" + packageID, "SYNOPKG_PKGVAR=" + filepath.Dir(canonical)}
			cmd.SysProcAttr = &syscall.SysProcAttr{Credential: &syscall.Credential{Uid: uint32(uid), Gid: uint32(gid)}}
			cmd.Stdin, cmd.Stdout = input, output
			// Native errors may contain private paths. Only fixed callback errors
			// reach Hyper Backup, never archive contents or process diagnostics.
			cmd.Stderr = io.Discard
			return cmd.Run()
		},
	}
	return hyperBackupTransfer(action, input, runtime)
}

func hyperBackupTransfer(action, input string, runtime hyperBackupRuntime) error {
	if action != "hyper-backup-export" && action != "hyper-backup-import" {
		return errors.New("Unknown application backup operation")
	}
	var request struct {
		Path    string `json:"temp_path"`
		Version string `json:"app_data_version"`
	}
	if len(input) > 65536 || json.Unmarshal([]byte(input), &request) != nil || !filepath.IsAbs(request.Path) || filepath.Clean(request.Path) == "/" || (request.Version != "" && request.Version != "1.0") {
		return errors.New("Invalid Hyper Backup application request")
	}
	path := filepath.Clean(request.Path)
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() {
		return errors.New("Hyper Backup must provide an existing staging directory")
	}
	archive := filepath.Join(path, applicationArchive)
	var file *os.File
	if action == "hyper-backup-import" {
		file, err = os.OpenFile(archive, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if err != nil {
			return errors.New("The application backup archive is missing or unreadable")
		}
		defer file.Close()
		info, err = file.Stat()
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 || info.Sys().(*syscall.Stat_t).Nlink != 1 {
			return errors.New("Invalid application backup archive")
		}
	} else {
		// Do not replace unrelated files, including links, in DSM's staging area.
		if _, err = os.Lstat(archive); !errors.Is(err, os.ErrNotExist) {
			return errors.New("Hyper Backup application staging is not empty")
		}
		file, err = os.CreateTemp(path, ".dagsverk-backup-")
		if err != nil {
			return errors.New("Cannot create the application backup archive")
		}
		defer file.Close()
		defer os.Remove(file.Name())
	}
	status, statusErr := runtime.command("status", packageID)
	var state struct {
		Status string `json:"status"`
		Code   *int   `json:"status_code"`
	}
	if json.Unmarshal(status, &state) != nil || state.Code == nil || *state.Code != 0 || (state.Status != "running" && state.Status != "stop" && state.Status != "stopped") || (statusErr != nil && state.Status == "running") {
		return errors.New("Cannot determine the package state; application data was not changed")
	}
	running := state.Status == "running"
	if running {
		if _, err = runtime.command("stop", packageID); err != nil {
			return errors.New("Cannot stop the package for application backup or restore")
		}
	}
	if action == "hyper-backup-export" {
		err = runtime.transfer("application-export", nil, file)
		if err == nil {
			err = file.Sync()
		}
		if err == nil {
			info, err = file.Stat()
			if err == nil && info.Size() == 0 {
				err = errors.New("Empty application backup")
			}
		}
		if err == nil {
			err = os.Rename(file.Name(), archive)
		}
	} else {
		err = runtime.transfer("application-import", file, io.Discard)
	}
	// Backup is read-only, so try to restore availability even after failure.
	// A failed restore must stay stopped for safe native-maintenance recovery.
	if running && (err == nil || action == "hyper-backup-export") {
		if _, startErr := runtime.command("start", packageID); startErr != nil {
			return errors.New("The package could not restart after application backup or restore; inspect Package Center")
		}
	}
	if err != nil {
		return errors.New("Application backup or restore failed; follow the native recovery instructions")
	}
	return nil
}

func applicationExport(root string, output io.Writer) error {
	path, err := backup(root)
	if err != nil {
		return err
	}
	defer os.Remove(path)
	archive, err := os.Open(path)
	if err != nil {
		return err
	}
	defer archive.Close()
	_, err = io.Copy(output, archive)
	return err
}

func applicationImport(root, scripts string, input io.Reader) error {
	base := filepath.Join(root, "backups")
	if err := os.MkdirAll(base, 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(base, ".hyper-backup-import-*.tar")
	if err != nil {
		return err
	}
	path := file.Name()
	defer file.Close()
	// An interrupted native restore retains this archive for operator recovery.
	// Successful imports and failures before maintenance consent need no copy.
	complete := false
	defer func() {
		if _, marker := os.Stat(filepath.Join(root, "config/maintenance-pending.json")); complete || errors.Is(marker, os.ErrNotExist) {
			os.Remove(path)
		}
	}()
	if _, err = io.Copy(file, input); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	if err = restore(root, path, filepath.Join(scripts, "migration")); err != nil {
		return err
	}
	complete = true
	return nil
}
