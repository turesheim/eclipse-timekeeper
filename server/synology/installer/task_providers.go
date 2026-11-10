package main

import (
	"errors"
	"os"
	"path/filepath"
)

const taskProviderConfiguration = "secrets/task_providers.properties"
const taskProviderDefaults = "# Optional OAuth app registrations; see TASK-PROVIDERS.md.\n" +
	"# timekeeper.tasks.google.client-id=\n# timekeeper.tasks.google.client-secret=\n" +
	"# timekeeper.tasks.microsoft.client-id=\n# timekeeper.tasks.microsoft.client-secret=\n"

// App registrations belong to retained private state, outside generated runtime
// files and Compose environment metadata. The server reads the mounted copy.
func ensureTaskProviderConfiguration(root string) error {
	path := filepath.Join(root, taskProviderConfiguration)
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() {
			return errors.New("Task-provider configuration must be a regular file")
		}
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return atomic(path, []byte(taskProviderDefaults), 0444)
}

// Backups made before task providers had no app-registration file. Restoring
// one must clear destination registrations rather than silently inherit them.
func restoreTaskProviderConfiguration(root, stage string) error {
	value, err := os.ReadFile(filepath.Join(stage, taskProviderConfiguration))
	if errors.Is(err, os.ErrNotExist) {
		value, err = []byte(taskProviderDefaults), nil
	}
	if err != nil {
		return err
	}
	return atomic(filepath.Join(root, taskProviderConfiguration), value, 0444)
}
