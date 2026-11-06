package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

func hyperFixture(t *testing.T, running bool, fail string) (string, hyperBackupRuntime, *[]string) {
	t.Helper()
	directory := t.TempDir()
	input := `{"temp_path":` + strconv.Quote(directory) + `,"app_data_version":"1.0"}`
	var calls []string
	status := "stop"
	if running {
		status = "running"
	}
	runtime := hyperBackupRuntime{
		command: func(args ...string) ([]byte, error) {
			if len(args) != 2 || args[1] != packageID {
				t.Fatalf("wrong package lifecycle command: %v", args)
			}
			calls = append(calls, args[0])
			if args[0] == fail {
				return nil, errors.New("private command diagnostic")
			}
			if args[0] == "status" {
				return []byte(`{"status":"` + status + `","status_code":0}`), nil
			}
			return nil, nil
		},
		transfer: func(action string, input io.Reader, output io.Writer) error {
			calls = append(calls, action)
			if action == "application-export" {
				io.WriteString(output, "complete private native archive")
			} else {
				value, err := io.ReadAll(input)
				if err != nil || string(value) != "complete private native archive" {
					t.Fatal("restore did not receive the exact archive through its input descriptor")
				}
			}
			if action == fail {
				return errors.New("private archive diagnostic")
			}
			return nil
		},
	}
	return input, runtime, &calls
}

func TestHyperBackupPublishesOnlyCompletedPrivateArchivesAndPreservesPackageState(t *testing.T) {
	for _, running := range []bool{false, true} {
		for _, identity := range []string{"timekeeper", "dagsverk"} {
			original := packageID
			packageID = identity
			input, runtime, calls := hyperFixture(t, running, "")
			if err := hyperBackupTransfer("hyper-backup-export", input, runtime); err != nil {
				t.Fatal(err)
			}
			var request struct {
				Path string `json:"temp_path"`
			}
			json.Unmarshal([]byte(input), &request)
			path := filepath.Join(request.Path, applicationArchive)
			value, _ := os.ReadFile(path)
			info, _ := os.Stat(path)
			if string(value) != "complete private native archive" || info.Mode().Perm() != 0600 {
				t.Fatal("incomplete or non-private application archive")
			}
			wanted := []string{"status", "application-export"}
			if running {
				wanted = []string{"status", "stop", "application-export", "start"}
			}
			if !reflect.DeepEqual(*calls, wanted) {
				t.Fatalf("package state changed: %v", *calls)
			}
			packageID = original
		}
	}
}

func TestHyperBackupFailuresDoNotPublishPartialArchivesAndRestartReadOnlySource(t *testing.T) {
	input, runtime, calls := hyperFixture(t, true, "application-export")
	err := hyperBackupTransfer("hyper-backup-export", input, runtime)
	if err == nil || strings.Contains(err.Error(), "private") {
		t.Fatal("backup failure was hidden or leaked a private diagnostic")
	}
	if !reflect.DeepEqual(*calls, []string{"status", "stop", "application-export", "start"}) {
		t.Fatalf("failed read-only backup did not restore availability: %v", *calls)
	}
	var request struct {
		Path string `json:"temp_path"`
	}
	json.Unmarshal([]byte(input), &request)
	entries, _ := os.ReadDir(request.Path)
	if len(entries) != 0 {
		t.Fatal("partial backup escaped into Hyper Backup staging")
	}
}

func TestHyperBackupRestoreLeavesFailedRecoveryStopped(t *testing.T) {
	for _, fail := range []string{"", "application-import"} {
		input, runtime, calls := hyperFixture(t, true, fail)
		var request struct {
			Path string `json:"temp_path"`
		}
		json.Unmarshal([]byte(input), &request)
		os.WriteFile(filepath.Join(request.Path, applicationArchive), []byte("complete private native archive"), 0600)
		err := hyperBackupTransfer("hyper-backup-import", input, runtime)
		if (err != nil) != (fail != "") {
			t.Fatalf("wrong restore result: %v", err)
		}
		wanted := []string{"status", "stop", "application-import"}
		if fail == "" {
			wanted = append(wanted, "start")
		}
		if !reflect.DeepEqual(*calls, wanted) {
			t.Fatalf("unsafe recovery lifecycle: %v", *calls)
		}
	}
}

func TestHyperBackupRejectsInvalidInputsBeforeStoppingThePackage(t *testing.T) {
	input, runtime, calls := hyperFixture(t, true, "")
	for _, invalid := range []string{"", `{}`, `{"temp_path":"relative"}`, strings.Replace(input, `"1.0"`, `"2.0"`, 1)} {
		if hyperBackupTransfer("hyper-backup-export", invalid, runtime) == nil {
			t.Fatal("accepted invalid Hyper Backup request")
		}
	}
	var request struct {
		Path string `json:"temp_path"`
	}
	json.Unmarshal([]byte(input), &request)
	archive := filepath.Join(request.Path, applicationArchive)
	other := filepath.Join(t.TempDir(), "other")
	os.WriteFile(other, []byte("unrelated data"), 0600)
	os.Symlink(other, archive)
	for _, action := range []string{"hyper-backup-export", "hyper-backup-import"} {
		if hyperBackupTransfer(action, input, runtime) == nil {
			t.Fatal("followed an archive symlink")
		}
	}
	os.Remove(archive)
	os.Link(other, archive)
	if hyperBackupTransfer("hyper-backup-import", input, runtime) == nil {
		t.Fatal("accepted a hard-linked restore archive")
	}
	if len(*calls) != 0 {
		t.Fatal("invalid request stopped the package")
	}
}

func TestHyperBackupRefusesAmbiguousStateAndReportsLifecycleFailures(t *testing.T) {
	for _, fail := range []string{"status", "stop", "start"} {
		input, runtime, calls := hyperFixture(t, true, fail)
		if hyperBackupTransfer("hyper-backup-export", input, runtime) == nil {
			t.Fatalf("ignored %s failure", fail)
		}
		if fail == "stop" && !reflect.DeepEqual(*calls, []string{"status", "stop"}) {
			t.Fatal("backup proceeded after failed stop")
		}
	}
	for _, status := range []string{`{}`, `{"status":"stop","status_code":263}`, `{"status":"start_failed","status_code":272}`} {
		input, runtime, calls := hyperFixture(t, true, "")
		runtime.command = func(...string) ([]byte, error) { return []byte(status), nil }
		if hyperBackupTransfer("hyper-backup-export", input, runtime) == nil || len(*calls) != 0 {
			t.Fatal("ambiguous state allowed application backup")
		}
	}
}
