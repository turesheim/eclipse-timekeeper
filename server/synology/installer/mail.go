package main

import (
	"errors"
	"os"
	"path/filepath"
)

// Operator-managed mail settings survive every runtime configuration refresh.
// The SMTP password is mounted separately and never enters Compose or properties.
func ensureMailConfiguration(root string) error {
	defaults := map[string]string{
		"config/mail.properties": "# Initial configuration or explicit operator recovery only.\n# After first startup, use Administration; normal restarts preserve central settings.\n" +
			"timekeeper.mail.enabled=false\ntimekeeper.auth.origin-epoch=1\n" +
			"quarkus.mailer.host=localhost\nquarkus.mailer.port=587\n" +
			"quarkus.mailer.from=timekeeper@localhost\nquarkus.mailer.username=\n" +
			"quarkus.mailer.tls=false\nquarkus.mailer.start-tls=REQUIRED\n",
		"secrets/smtp_password": "",
	}
	for name, value := range defaults {
		path := filepath.Join(root, name)
		if _, err := os.Stat(path); err == nil {
			continue
		} else if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		mode := os.FileMode(0644)
		if name == "secrets/smtp_password" {
			mode = 0444
		}
		if err := atomic(path, []byte(value), mode); err != nil {
			return err
		}
	}
	return nil
}
