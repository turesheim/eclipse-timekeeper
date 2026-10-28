package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// DSM creates the project before postinst. Set the container's unprivileged
// identity in the incoming payload, while retaining the permanent appdata mount
// across package upgrades. This never modifies the installed payload or data.
func prepareInstanceProject(incoming, root string) error {
	if incoming == "" {
		return errors.New("Missing incoming DSM payload")
	}
	path := filepath.Join(incoming, "project/compose.yaml")
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		return errors.New("Invalid incoming Container Manager project")
	}
	var project map[string]any
	if err = readJSON(path, &project); err != nil {
		return errors.New("Invalid incoming Container Manager project")
	}
	services, ok := project["services"].(map[string]any)
	if !ok || len(services) != 1 {
		return errors.New("Invalid incoming Container Manager project")
	}
	server, ok := services["server"].(map[string]any)
	if !ok {
		return errors.New("Invalid incoming Container Manager project")
	}
	if os.Getuid() <= 0 || os.Getgid() <= 0 {
		return errors.New("Run the package controller as its dedicated DSM package account")
	}
	parent := filepath.Dir(root)
	if !filepath.IsAbs(parent) || filepath.Clean(parent) != parent || parent == "/" {
		return errors.New("Invalid permanent DSM appdata")
	}
	info, err = os.Stat(parent)
	if err != nil || !info.IsDir() {
		return errors.New("DSM permanent appdata must exist before project creation")
	}
	volumes, ok := server["volumes"].([]any)
	if !ok {
		return errors.New("Invalid incoming Container Manager project")
	}
	found := false
	for _, raw := range volumes {
		volume, ok := raw.(map[string]any)
		if !ok {
			return errors.New("Invalid incoming Container Manager project")
		}
		if volume["target"] == "/state" {
			if found || volume["type"] != "bind" || volume["read_only"] != false {
				return errors.New("Invalid instance state mount")
			}
			volume["source"] = parent
			found = true
		}
	}
	if !found {
		return errors.New("Missing private instance state mount")
	}
	server["user"] = fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())
	content, err := json.MarshalIndent(project, "", "  ")
	if err != nil {
		return err
	}
	return atomic(path, append(content, '\n'), 0644)
}
