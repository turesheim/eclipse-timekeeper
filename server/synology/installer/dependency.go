package main

import (
	"errors"
	"os"
	"strconv"
	"strings"
)

func containerManagerAt(path string) error {
	content, err := os.ReadFile(path)
	if err != nil {
		return errors.New("Install Synology Container Manager before Timekeeper")
	}
	for _, line := range strings.Split(string(content), "\n") {
		key, value, found := strings.Cut(line, "=")
		if !found || strings.TrimSpace(key) != "version" {
			continue
		}
		version := strings.Trim(strings.TrimSpace(value), `"`)
		cut := strings.LastIndex(version, "-")
		if cut >= 0 {
			build, err := strconv.Atoi(version[cut+1:])
			if err == nil && build >= 1432 {
				return nil
			}
		}
	}
	return errors.New("Update Container Manager to build 1432 or later for Docker Project worker support")
}
