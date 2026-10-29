package main

import (
	"archive/tar"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixtureNativeMaterial(t *testing.T, root string) *nativeMaterial {
	t.Helper()
	generation := "11111111-1111-4111-8111-111111111111"
	directory := filepath.Join(root, "instance-restore/material", generation)
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	metadata := materialMetadata{Format: materialFormat, Generation: generation, Operation: "22222222-2222-4222-8222-222222222222", IntentDigest: strings.Repeat("a", 64), Entries: map[string]materialEntry{}}
	for name, content := range map[string]string{"application.properties": "timekeeper.instance-restore.storage=/state/server/instance-restore\n", "source-configuration.json": "{}", "source/configuration/0000.json": "[]"} {
		path := filepath.Join(directory, name)
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		digest, err := fileDigest(path)
		if err != nil {
			t.Fatal(err)
		}
		metadata.Entries[name] = materialEntry{Bytes: int64(len(content)), SHA256: digest}
	}
	if err := writeJSON(filepath.Join(directory, "material.json"), metadata, 0600); err != nil {
		t.Fatal(err)
	}
	digest, err := fileDigest(filepath.Join(directory, "material.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := writeJSON(filepath.Join(root, "instance-restore/active.json"), materialPointer{materialFormat, generation, digest}, 0600); err != nil {
		t.Fatal(err)
	}
	material, err := nativeMaterialInventory(root)
	if err != nil {
		t.Fatal(err)
	}
	return material
}

func TestNativeMaterialInventoryAndRecoveryPreserveOnlyActiveImmutableFiles(t *testing.T) {
	source := t.TempDir()
	material := fixtureNativeMaterial(t, source)
	if err := os.WriteFile(filepath.Join(source, "instance-restore/prepared.json"), []byte("uncommitted"), 0600); err != nil {
		t.Fatal(err)
	}
	if len(material.Files) != 5 {
		t.Fatalf("unexpected active inventory: %d", len(material.Files))
	}
	target := t.TempDir()
	if err := installNativeMaterial(target, source, material); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(target, "instance-restore/active.json")); !os.IsNotExist(err) {
		t.Fatal("published material before database commit")
	}
	if err := publishNativeMaterial(target, source, material); err != nil {
		t.Fatal(err)
	}
	actual, err := nativeMaterialInventory(target)
	if err != nil {
		t.Fatal(err)
	}
	if actual.Generation != material.Generation || actual.Digest != material.Digest || actual.IntentDigest != material.IntentDigest {
		t.Fatal("changed immutable material identity")
	}
	if _, err := os.Stat(filepath.Join(target, "instance-restore/prepared.json")); !os.IsNotExist(err) {
		t.Fatal("copied uncommitted consent")
	}
	if err = publishNativeMaterial(target, source, nil); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(target, "instance-restore/material", material.Generation)); err != nil {
		t.Fatal("removed immutable material on pointer removal")
	}
}

func TestNativeMaterialRejectsTamperingPermissionsLinksAndUnexpectedInputs(t *testing.T) {
	for _, kind := range []string{"bytes", "permissions", "symlink", "hardlink", "unknown", "traversal", "digest", "duplicate"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			material := fixtureNativeMaterial(t, root)
			directory := filepath.Join(root, "instance-restore/material", material.Generation)
			path := filepath.Join(directory, "source/configuration/0000.json")
			switch kind {
			case "bytes":
				_ = os.WriteFile(path, []byte("{}"), 0600)
			case "permissions":
				_ = os.Chmod(path, 0644)
			case "symlink":
				_ = os.Remove(path)
				if err := os.Symlink(filepath.Join(directory, "source-configuration.json"), path); err != nil {
					t.Fatal(err)
				}
			case "hardlink":
				if err := os.Link(path, filepath.Join(root, "link")); err != nil {
					t.Fatal(err)
				}
			case "duplicate":
				path := filepath.Join(directory, "material.json")
				content, err := os.ReadFile(path)
				if err != nil {
					t.Fatal(err)
				}
				content = append([]byte(`{"format":"untrusted",`), content[1:]...)
				if err = os.WriteFile(path, content, 0600); err != nil {
					t.Fatal(err)
				}
				digest, err := fileDigest(path)
				if err != nil {
					t.Fatal(err)
				}
				if err = writeJSON(filepath.Join(root, "instance-restore/active.json"), materialPointer{materialFormat, material.Generation, digest}, 0600); err != nil {
					t.Fatal(err)
				}
			case "digest":
				var pointer materialPointer
				_ = readJSON(filepath.Join(root, "instance-restore/active.json"), &pointer)
				pointer.SHA256 = strings.Repeat("b", 64)
				_ = writeJSON(filepath.Join(root, "instance-restore/active.json"), pointer, 0600)
			default:
				var metadata materialMetadata
				_ = readJSON(filepath.Join(directory, "material.json"), &metadata)
				name := "unlisted-secret"
				if kind == "traversal" {
					name = "../../escape"
				}
				metadata.Entries[name] = materialEntry{Bytes: 0, SHA256: strings.Repeat("a", 64)}
				_ = writeJSON(filepath.Join(directory, "material.json"), metadata, 0600)
				digest, _ := fileDigest(filepath.Join(directory, "material.json"))
				_ = writeJSON(filepath.Join(root, "instance-restore/active.json"), materialPointer{materialFormat, material.Generation, digest}, 0600)
			}
			if _, err := nativeMaterialInventory(root); err == nil {
				t.Fatal("accepted invalid native instance material")
			}
		})
	}
	for _, name := range []string{"instance-restore/../escape", "instance-restore/material/../active.json", "instance-restore/material/11111111-1111-4111-8111-111111111111/source/configuration/../../escape", "instance-restore/prepared.json"} {
		if validNativeMaterialMember(name) {
			t.Fatalf("allowed invalid archive path %s", name)
		}
	}
}

func TestNativeBackupFormatTwoRequiresCompleteAttestedInventory(t *testing.T) {
	for _, variant := range []string{"valid", "legacy", "missing", "extra", "wrong-hash"} {
		t.Run(variant, func(t *testing.T) {
			source := t.TempDir()
			material := fixtureNativeMaterial(t, source)
			files := append(backupFilesFor(9), material.Files...)
			manifest := backupManifest{Format: 2, ServerSchema: 9, PostgreSQLMajor: 17, Files: map[string]string{}}
			for _, name := range backupFilesFor(9) {
				path := filepath.Join(source, name)
				_ = os.MkdirAll(filepath.Dir(path), 0700)
				if err := os.WriteFile(path, []byte("fixture"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			for _, name := range files {
				digest, err := fileDigest(filepath.Join(source, name))
				if err != nil {
					t.Fatal(err)
				}
				manifest.Files[name] = digest
			}
			switch variant {
			case "legacy":
				manifest.Format = 1
			case "missing":
				files = files[:len(files)-1]
			case "wrong-hash":
				manifest.Files[material.Files[0]] = strings.Repeat("b", 64)
			case "extra":
				name := "instance-restore/material/" + material.Generation + "/source/configuration/9999.json"
				_ = os.WriteFile(filepath.Join(source, name), []byte("{}"), 0600)
				files = append(files, name)
				manifest.Files[name], _ = fileDigest(filepath.Join(source, name))
			}
			output, err := os.CreateTemp(t.TempDir(), "native-*.tar")
			if err != nil {
				t.Fatal(err)
			}
			writer := tar.NewWriter(output)
			value, _ := json.Marshal(manifest)
			_ = writer.WriteHeader(&tar.Header{Name: "manifest.json", Mode: 0600, Size: int64(len(value))})
			_, _ = writer.Write(value)
			for _, name := range files {
				content, err := os.ReadFile(filepath.Join(source, name))
				if err != nil {
					t.Fatal(err)
				}
				_ = writer.WriteHeader(&tar.Header{Name: name, Mode: 0600, Size: int64(len(content))})
				_, _ = writer.Write(content)
			}
			_ = writer.Close()
			_ = output.Close()
			_, err = unpackBackup(output.Name(), t.TempDir())
			if (err == nil) != (variant == "valid") {
				t.Fatalf("unexpected archive result for %s: %v", variant, err)
			}
		})
	}
}

func TestIncomingProjectUsesDedicatedPackageIdentityAndRetainedAppdata(t *testing.T) {
	incoming := t.TempDir()
	if err := os.Mkdir(filepath.Join(incoming, "project"), 0755); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile("../project/compose.json")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(incoming, "project/compose.yaml")
	if err = os.WriteFile(path, content, 0644); err != nil {
		t.Fatal(err)
	}
	parent := t.TempDir()
	root := filepath.Join(parent, "server")
	if err = prepareInstanceProject(incoming, root); err != nil {
		t.Fatal(err)
	}
	var project map[string]any
	if err = readJSON(path, &project); err != nil {
		t.Fatal(err)
	}
	server := project["services"].(map[string]any)["server"].(map[string]any)
	if server["user"] != fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()) {
		t.Fatal("container identity does not match DSM package account")
	}
	if server["cap_drop"].([]any)[0] != "ALL" || server["read_only"] != true {
		t.Fatal("changed container restrictions")
	}
	for _, raw := range server["volumes"].([]any) {
		volume := raw.(map[string]any)
		if volume["target"] == "/state" && volume["source"] != parent {
			t.Fatal("mounted transient package payload instead of permanent appdata")
		}
	}
	if _, err = os.Stat(root); !os.IsNotExist(err) {
		t.Fatal("preinst validation created installation data")
	}
	if err = prepareInstanceProject(incoming, root); err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err = os.Symlink(filepath.Join(parent, "escape"), path); err != nil {
		t.Fatal(err)
	}
	if prepareInstanceProject(incoming, root) == nil {
		t.Fatal("followed incoming project symlink")
	}
}
