package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
)

const materialFormat = "timekeeper-instance-material-v1"

var materialUUID = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`)
var materialDigest = regexp.MustCompile(`^[a-f0-9]{64}$`)
var materialInput = regexp.MustCompile(`^source/configuration/[0-9]{4}\.(bin|p12|pfx|jks|pem|crt|key|json|properties|der)$`)

type nativeMaterial struct {
	Generation   string
	Operation    string
	Digest       string
	IntentDigest string
	Files        []string
}
type materialPointer struct {
	Format     string `json:"format"`
	Generation string `json:"generation"`
	SHA256     string `json:"sha256"`
}
type materialEntry struct {
	Bytes  int64  `json:"bytes"`
	SHA256 string `json:"sha256"`
}
type materialMetadata struct {
	Format       string                   `json:"format"`
	Generation   string                   `json:"generation"`
	Operation    string                   `json:"operation"`
	IntentDigest string                   `json:"intentDigest"`
	Entries      map[string]materialEntry `json:"entries"`
}

// Only the committed immutable generation is native maintenance state. Uploaded
// ciphertext, staged plaintext and process locks are deliberately not copied.
func nativeMaterialInventory(root string) (*nativeMaterial, error) {
	base := filepath.Join(root, "instance-restore")
	if _, err := os.Lstat(filepath.Join(base, "active.json")); errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err := privateNativePath(root, base, true); err != nil {
		return nil, err
	}
	if err := privateNativePath(base, filepath.Join(base, "active.json"), false); err != nil {
		return nil, err
	}
	var pointer materialPointer
	if err := readNativeMaterialJSON(filepath.Join(base, "active.json"), 1024, &pointer); err != nil || pointer.Format != materialFormat || !materialUUID.MatchString(pointer.Generation) || !materialDigest.MatchString(pointer.SHA256) {
		return nil, errors.New("Invalid active instance material")
	}
	directory := filepath.Join(base, "material", pointer.Generation)
	metadataPath := filepath.Join(directory, "material.json")
	if err := privateNativePath(base, metadataPath, false); err != nil {
		return nil, err
	}
	digest, err := fileDigest(metadataPath)
	if err != nil || digest != pointer.SHA256 {
		return nil, errors.New("Invalid active instance material checksum")
	}
	var metadata materialMetadata
	if err = readNativeMaterialJSON(metadataPath, 131072, &metadata); err != nil || metadata.Format != materialFormat || metadata.Generation != pointer.Generation || !materialUUID.MatchString(metadata.Operation) || !materialDigest.MatchString(metadata.IntentDigest) || len(metadata.Entries) < 2 || len(metadata.Entries) > 66 {
		return nil, errors.New("Invalid active instance material inventory")
	}
	if _, ok := metadata.Entries["application.properties"]; !ok {
		return nil, errors.New("Missing recovered instance configuration")
	}
	if _, ok := metadata.Entries["source-configuration.json"]; !ok {
		return nil, errors.New("Missing recovered source inventory")
	}
	result := &nativeMaterial{Generation: pointer.Generation, Operation: metadata.Operation, Digest: digest, IntentDigest: metadata.IntentDigest, Files: []string{"instance-restore/active.json", "instance-restore/material/" + pointer.Generation + "/material.json"}}
	var total int64
	for name, entry := range metadata.Entries {
		limit := int64(20_000_000)
		switch name {
		case "application.properties":
			limit = 1_000_000
		case "source-configuration.json":
			limit = 4_000_000
		default:
			if !materialInput.MatchString(name) {
				return nil, errors.New("Invalid instance material path")
			}
		}
		path := filepath.Join(directory, filepath.FromSlash(name))
		if err = privateNativePath(base, path, false); err != nil {
			return nil, err
		}
		info, err := os.Lstat(path)
		if err != nil {
			return nil, err
		}
		if entry.Bytes < 0 || entry.Bytes > limit || info.Size() != entry.Bytes || !materialDigest.MatchString(entry.SHA256) {
			return nil, errors.New("Invalid instance material size")
		}
		actual, err := fileDigest(path)
		if err != nil || actual != entry.SHA256 {
			return nil, errors.New("Invalid instance material checksum")
		}
		total += entry.Bytes
		if total > 70_000_000 {
			return nil, errors.New("Instance material is too large")
		}
		result.Files = append(result.Files, "instance-restore/material/"+pointer.Generation+"/"+name)
	}
	sort.Strings(result.Files)
	return result, nil
}

func privateNativePath(root, path string, directory bool) error {
	relative, err := filepath.Rel(root, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return errors.New("Invalid private instance path")
	}
	current := root
	parts := strings.Split(relative, string(filepath.Separator))
	for i, part := range parts {
		if part != "." {
			current = filepath.Join(current, part)
		}
		info, err := os.Lstat(current)
		if err != nil {
			return err
		}
		isDirectory := i < len(parts)-1 || directory
		mode := os.FileMode(0600)
		if isDirectory {
			mode = 0700
		}
		if (isDirectory && !info.IsDir()) || (!isDirectory && !info.Mode().IsRegular()) || info.Mode().Perm() != mode {
			return errors.New("Instance material must contain private regular files and directories")
		}
		if !isDirectory {
			if stat, ok := info.Sys().(*syscall.Stat_t); !ok || stat.Nlink != 1 {
				return errors.New("Instance material cannot contain hard links")
			}
		}
	}
	return nil
}

func validNativeMaterialMember(name string) bool {
	if name == "instance-restore/active.json" {
		return true
	}
	parts := strings.SplitN(name, "/", 4)
	return len(parts) == 4 && parts[0] == "instance-restore" && parts[1] == "material" && materialUUID.MatchString(parts[2]) && (parts[3] == "material.json" || parts[3] == "application.properties" || parts[3] == "source-configuration.json" || materialInput.MatchString(parts[3]))
}

func nativeMaterialControl(root string, s Settings, password string) (*nativeMaterial, error) {
	material, err := nativeMaterialInventory(root)
	if err != nil {
		return nil, err
	}
	if s.Release.Schema < 9 {
		if material != nil {
			return nil, errors.New("Instance material requires schema 9")
		}
		return nil, nil
	}
	value, err := psql(nativePSQL, s.DatabasePort, s.Role, password, s.Database, "SELECT COALESCE((SELECT payload::jsonb FROM tk_instance WHERE kind='CONTROL'),'{}'::jsonb)::text;")
	if err != nil {
		return nil, err
	}
	var control map[string]json.RawMessage
	if err = json.Unmarshal([]byte(value), &control); err != nil {
		return nil, errors.New("Invalid instance control")
	}
	var pending bool
	if raw, ok := control["restorePending"]; ok {
		if json.Unmarshal(raw, &pending) != nil || pending {
			return nil, errors.New("Complete or cancel the pending instance restore before native maintenance")
		}
	}
	var restored struct {
		Phase     string `json:"phase"`
		Operation string `json:"operationId"`
		SHA       string `json:"materialSha256"`
	}
	if raw, ok := control["restore"]; ok {
		if json.Unmarshal(raw, &restored) != nil {
			return nil, errors.New("Invalid instance control")
		}
	}
	var generation string
	_ = json.Unmarshal(control["restoreGeneration"], &generation)
	var intent struct {
		SHA string `json:"sha256"`
	}
	_ = json.Unmarshal(control["restoreIntent"], &intent)
	if restored.Phase == "COMPLETE" {
		if material == nil || material.Generation != generation || material.Operation != restored.Operation || material.Digest != restored.SHA || material.IntentDigest != intent.SHA {
			return nil, errors.New("Native backup requires the database's complete active restore material")
		}
	} else if material != nil {
		return nil, errors.New("Active material does not match the instance database")
	}
	return material, nil
}

// Executed inside the native SQL restore transaction, before committing any
// replacement. A database without its matching files must never be published.
func nativeMaterialRestoreSQL(material *nativeMaterial, schema int) string {
	if schema < 9 {
		return ""
	}
	condition := "COALESCE(payload::jsonb->>'restorePending','false')='false' AND COALESCE(payload::jsonb#>>'{restore,phase}','')<>'COMPLETE'"
	if material != nil {
		condition = fmt.Sprintf("COALESCE(payload::jsonb->>'restorePending','false')='false' AND payload::jsonb->>'restoreGeneration'='%s' AND payload::jsonb#>>'{restore,phase}'='COMPLETE' AND payload::jsonb#>>'{restore,operationId}'='%s' AND payload::jsonb#>>'{restore,materialSha256}'='%s' AND payload::jsonb#>>'{restoreIntent,sha256}'='%s'", material.Generation, material.Operation, material.Digest, material.IntentDigest)
	}
	// All interpolated values were validated as UUIDs or SHA-256 digests.
	return "\nDO $material$ BEGIN IF EXISTS (SELECT 1 FROM tk_instance WHERE kind='CONTROL' AND NOT COALESCE((" + condition + "),false))" + func() string {
		if material != nil {
			return " OR NOT EXISTS (SELECT 1 FROM tk_instance WHERE kind='CONTROL')"
		}
		return ""
	}() + " THEN RAISE EXCEPTION 'Instance material is missing or inconsistent'; END IF; END $material$;\n"
}

func privateNativeDirectory(path string) error {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		if err = os.Mkdir(path, 0700); err != nil {
			return err
		}
		return syncNativeDirectory(filepath.Dir(path))
	}
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode().Perm() != 0700 {
		return errors.New("Invalid private native material directory")
	}
	return nil
}

// Install immutable files before committing the database. A failed transaction
// leaves only an unreferenced generation; the original active pointer is intact.
func installNativeMaterial(root, stage string, material *nativeMaterial) error {
	if material == nil {
		return nil
	}
	base := filepath.Join(root, "instance-restore")
	if err := privateNativeDirectory(base); err != nil {
		return err
	}
	parent := filepath.Join(base, "material")
	if err := privateNativeDirectory(parent); err != nil {
		return err
	}
	destination := filepath.Join(parent, material.Generation)
	if _, err := os.Lstat(destination); errors.Is(err, os.ErrNotExist) {
		source := filepath.Join(stage, "instance-restore/material", material.Generation)
		if err := syncNativeTree(source); err != nil {
			return err
		}
		if err := os.Rename(source, destination); err != nil {
			return err
		}
		return syncNativeDirectory(parent)
	} else if err != nil {
		return err
	}
	for _, name := range material.Files {
		if name == "instance-restore/active.json" {
			continue
		}
		path := filepath.Join(root, filepath.FromSlash(name))
		if err := privateNativePath(base, path, false); err != nil {
			return err
		}
		original, err := fileDigest(filepath.Join(stage, filepath.FromSlash(name)))
		if err != nil {
			return err
		}
		actual, err := fileDigest(path)
		if err != nil || actual != original {
			return errors.New("Existing generation differs from native recovery material")
		}
	}
	return nil
}

func publishNativeMaterial(root, stage string, material *nativeMaterial) error {
	base := filepath.Join(root, "instance-restore")
	if material == nil {
		err := os.Remove(filepath.Join(base, "active.json"))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil {
			return err
		}
		return syncNativeDirectory(base)
	}
	content, err := os.ReadFile(filepath.Join(stage, "instance-restore/active.json"))
	if err != nil {
		return err
	}
	if err = atomic(filepath.Join(base, "active.json"), content, 0600); err != nil {
		return err
	}
	return syncNativeDirectory(base)
}

func syncNativeDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
func syncNativeTree(path string) error {
	entries, err := os.ReadDir(path)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.IsDir() {
			if err = syncNativeTree(filepath.Join(path, entry.Name())); err != nil {
				return err
			}
		}
	}
	return syncNativeDirectory(path)
}

func readNativeMaterialJSON(path string, limit int64, target any) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if info.Size() > limit {
		return errors.New("Native material metadata is too large")
	}
	input, err := os.Open(path)
	if err != nil {
		return err
	}
	defer input.Close()
	body, err := io.ReadAll(io.LimitReader(input, limit+1))
	if err != nil {
		return err
	}
	if int64(len(body)) > limit {
		return errors.New("Native material metadata is too large")
	}
	tokens := json.NewDecoder(bytes.NewReader(body))
	if err = uniqueNativeJSON(tokens, 0); err != nil {
		return err
	}
	if _, err = tokens.Token(); err != io.EOF {
		return errors.New("Unexpected native material content")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}
func uniqueNativeJSON(decoder *json.Decoder, depth int) error {
	if depth > 8 {
		return errors.New("Native material metadata is too deep")
	}
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	if delimiter, ok := token.(json.Delim); ok {
		switch delimiter {
		case '{':
			names := map[string]bool{}
			for decoder.More() {
				key, err := decoder.Token()
				if err != nil {
					return err
				}
				name, ok := key.(string)
				if !ok || names[name] {
					return errors.New("Duplicate native material field")
				}
				names[name] = true
				if err = uniqueNativeJSON(decoder, depth+1); err != nil {
					return err
				}
			}
		case '[':
			for decoder.More() {
				if err = uniqueNativeJSON(decoder, depth+1); err != nil {
					return err
				}
			}
		default:
			return errors.New("Invalid native material JSON")
		}
		_, err = decoder.Token()
		return err
	}
	return nil
}
