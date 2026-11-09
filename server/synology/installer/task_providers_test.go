package main

import (
	"archive/tar"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTaskProviderRegistrationsSurviveConfigurationRefresh(t *testing.T) {
	root, target := t.TempDir(), t.TempDir()
	for _, name := range []string{"config", "secrets"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"config/tokens.json", "config/account-setup.json", "secrets/database_password"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("fixture"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := ensureTaskProviderConfiguration(root); err != nil {
		t.Fatal(err)
	}
	registration := []byte("timekeeper.tasks.google.client-id=fixture-id\ntimekeeper.tasks.google.client-secret=synthetic-private-registration\n")
	if err := atomic(filepath.Join(root, taskProviderConfiguration), registration, 0444); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := publishConfiguration(root, target, fixtureWizard(t).Settings); err != nil {
			t.Fatal(err)
		}
		for _, path := range []string{filepath.Join(root, taskProviderConfiguration), filepath.Join(target, "private", taskProviderConfiguration)} {
			value, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(value, registration) {
				t.Fatal("refresh replaced retained app registrations")
			}
			info, err := os.Stat(path)
			if err != nil || info.Mode().Perm() != 0444 {
				t.Fatal("mounted registration file is not read-only")
			}
		}
		value, err := os.ReadFile(filepath.Join(target, "private/config/runtime.properties"))
		if err != nil || bytes.Contains(value, []byte("synthetic-private-registration")) {
			t.Fatal("registration leaked into generated runtime settings")
		}
	}
	project, err := os.ReadFile("../project/compose.json")
	if err != nil || !bytes.Contains(project, []byte("file:/run/secrets/task_providers.properties")) {
		t.Fatal("private app registrations are not loaded")
	}
}

func TestTaskProviderConfigurationRejectsSymlinks(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "secrets"), 0700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "unrelated")
	if err := os.WriteFile(outside, []byte("untouched"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, taskProviderConfiguration)); err != nil {
		t.Fatal(err)
	}
	if ensureTaskProviderConfiguration(root) == nil {
		t.Fatal("accepted a registration symlink")
	}
	if restoreTaskProviderConfiguration(root, t.TempDir()) == nil {
		t.Fatal("restored through a registration symlink")
	}
	value, _ := os.ReadFile(outside)
	if string(value) != "untouched" {
		t.Fatal("changed unrelated data")
	}
}

func TestTaskProviderBackupRetainsRegistrationsAndReadsLegacyArchives(t *testing.T) {
	for _, variant := range []string{"current", "legacy", "wrong-checksum", "unlisted", "missing"} {
		t.Run(variant, func(t *testing.T) {
			members := map[string][]byte{}
			manifest := backupManifest{Format: 1, ServerSchema: currentSchema, PostgreSQLMajor: 17, Files: map[string]string{}}
			for _, name := range backupFilesFor(currentSchema) {
				members[name] = []byte("fixture")
			}
			if variant != "legacy" {
				members[taskProviderConfiguration] = []byte("timekeeper.tasks.microsoft.client-secret=synthetic-registration\n")
			}
			for name, value := range members {
				digest := sha256.Sum256(value)
				manifest.Files[name] = hex.EncodeToString(digest[:])
			}
			switch variant {
			case "wrong-checksum":
				manifest.Files[taskProviderConfiguration] = strings.Repeat("0", 64)
			case "unlisted":
				delete(manifest.Files, taskProviderConfiguration)
			case "missing":
				delete(members, taskProviderConfiguration)
			}
			members["manifest.json"], _ = json.Marshal(manifest)
			var archive bytes.Buffer
			writer := tar.NewWriter(&archive)
			for name, value := range members {
				if err := writer.WriteHeader(&tar.Header{Name: name, Mode: 0600, Size: int64(len(value))}); err != nil {
					t.Fatal(err)
				}
				if _, err := writer.Write(value); err != nil {
					t.Fatal(err)
				}
			}
			if err := writer.Close(); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(t.TempDir(), "backup.tar")
			if err := os.WriteFile(path, archive.Bytes(), 0600); err != nil {
				t.Fatal(err)
			}
			stage := t.TempDir()
			_, err := unpackBackup(path, stage)
			valid := variant == "current" || variant == "legacy"
			if (err == nil) != valid {
				t.Fatalf("unexpected backup validation for %s: %v", variant, err)
			}
			if !valid {
				return
			}
			root := t.TempDir()
			if err := os.Mkdir(filepath.Join(root, "secrets"), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(root, taskProviderConfiguration), []byte("destination-registration"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := restoreTaskProviderConfiguration(root, stage); err != nil {
				t.Fatal(err)
			}
			value, err := os.ReadFile(filepath.Join(root, taskProviderConfiguration))
			if err != nil {
				t.Fatal(err)
			}
			expected := string(members[taskProviderConfiguration])
			if variant == "legacy" {
				expected = taskProviderDefaults
			}
			if string(value) != expected {
				t.Fatal("restore lost the matching registration or inherited destination credentials")
			}
		})
	}
}
