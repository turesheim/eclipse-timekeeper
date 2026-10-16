package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type tailscaleFixture struct {
	state, host     string
	https, magicDNS bool
	offline         bool
	config          map[string]any
	requests        int
}

func newTailscaleFixture() *tailscaleFixture {
	return &tailscaleFixture{state: "Running", host: "nas.example.ts.net", https: true, magicDNS: true, config: map[string]any{}}
}
func (f *tailscaleFixture) api() tailscaleAPI {
	return tailscaleAPI{&http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Method != "GET" {
			panic("integration attempted to modify Tailscale")
		}
		f.requests++
		if f.offline {
			return nil, errors.New("offline fixture")
		}
		var value any
		switch r.URL.Path {
		case "/localapi/v0/status":
			caps := []string{}
			if f.https {
				caps = append(caps, "https")
			}
			value = map[string]any{"BackendState": f.state, "CurrentTailnet": map[string]any{"MagicDNSEnabled": f.magicDNS}, "Self": map[string]any{"DNSName": f.host + ".", "Capabilities": caps}}
		case "/localapi/v0/serve-config":
			value = f.config
		default:
			panic("unexpected Tailscale endpoint")
		}
		data, _ := json.Marshal(value)
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(bytes.NewReader(data))}, nil
	})}}
}
func selectedTailscale(t *testing.T) Settings {
	t.Helper()
	s := fixtureWizard(t).Settings
	s.Tailscale = true
	s.PublicOrigin = "https://nas.example.ts.net:8443"
	return s
}
func TestTailscaleDetectionExplainsRequiredSetup(t *testing.T) {
	for _, kind := range []string{"absent", "signed-out", "no-dns", "magicdns-off", "no-https"} {
		t.Run(kind, func(t *testing.T) {
			f := newTailscaleFixture()
			switch kind {
			case "absent":
				f.offline = true
			case "signed-out":
				f.state = "NeedsLogin"
			case "no-dns":
				f.host = "nas.local"
			case "magicdns-off":
				f.magicDNS = false
			case "no-https":
				f.https = false
			}
			if _, err := f.api().hostname(); err == nil {
				t.Fatal("missing prerequisite went undetected")
			}
		})
	}
	f := newTailscaleFixture()
	host, err := f.api().hostname()
	if err != nil || host != f.host {
		t.Fatal("could not detect hostname")
	}
}
func TestTailscaleWizardDerivesOriginAndManualAccessDoesNotNeedTailscale(t *testing.T) {
	s := fixtureWizard(t).Settings
	env := func(key string) string {
		return map[string]string{"wizard_tailscale": "true", "wizard_tailscale_port": "18443", "wizard_public_origin": "https://ignored.invalid"}[key]
	}
	got, err := configureAccessWith(s, env, func() (string, error) { return "nas.example.ts.net", nil })
	if err != nil || !got.Tailscale || got.PublicOrigin != "https://nas.example.ts.net:18443" {
		t.Fatalf("incorrect detected access: %+v %v", got, err)
	}
	for _, port := range []string{"443", "65536", "8443/unsafe"} {
		_, err := configureAccessWith(s, func(key string) string {
			if key == "wizard_tailscale_port" {
				return port
			}
			return env(key)
		}, func() (string, error) { return "nas.example.ts.net", nil })
		if err == nil {
			t.Fatal("accepted invalid port")
		}
	}
	off := func(key string) string {
		return map[string]string{"wizard_tailscale": "false", "wizard_public_origin": "https://manual.example"}[key]
	}
	got, err = configureAccessWith(got, off, func() (string, error) { t.Fatal("manual access tried to reach Tailscale"); return "", nil })
	if err != nil || got.Tailscale || got.PublicOrigin != "https://manual.example" {
		t.Fatal("could not switch to manual access")
	}
	f := newTailscaleFixture()
	f.offline = true
	if err = f.api().validate(got); err != nil || f.requests != 0 {
		t.Fatal("manual access requires Tailscale")
	}
	for _, enabled := range []bool{false, true} {
		s.Tailscale = enabled
		got, err := configureAccessWith(s, func(string) string { return "" }, func() (string, error) { t.Fatal("upgrade reconfigured Tailscale"); return "", nil })
		if err != nil || got != s {
			t.Fatal("upgrade reset access")
		}
	}
}
func TestTailscalePreflightIsReadOnlyAndRefusesOtherServices(t *testing.T) {
	s := selectedTailscale(t)
	f := newTailscaleFixture()
	if err := f.api().validate(s); err != nil {
		t.Fatal(err)
	}
	f.config = map[string]any{"TCP": map[string]any{"8443": map[string]any{"HTTPS": true}}, "Web": map[string]any{"nas.example.ts.net:8443": map[string]any{"Handlers": map[string]any{"/": map[string]any{"Proxy": "http://127.0.0.1:8098"}}}}, "UnknownValue": json.Number("9007199254740993")}
	before, _ := json.Marshal(f.config)
	if err := f.api().validate(s); err != nil {
		t.Fatal("existing Timekeeper rule was refused:", err)
	}
	after, _ := json.Marshal(f.config)
	if !bytes.Equal(before, after) {
		t.Fatal("preflight modified forwarding")
	}
	s.HTTPPort = 9000
	if err := f.api().validate(s); err == nil {
		t.Fatal("accepted another service's rule")
	}
}
func TestTailscalePreflightRefusesFunnelAndForegroundEvenWithoutListener(t *testing.T) {
	for _, kind := range []string{"funnel", "foreground", "tcp"} {
		t.Run(kind, func(t *testing.T) {
			f := newTailscaleFixture()
			switch kind {
			case "funnel":
				f.config = map[string]any{"AllowFunnel": map[string]any{"nas.example.ts.net:8443": true}}
			case "foreground":
				f.config = map[string]any{"Foreground": map[string]any{"session": map[string]any{"TCP": map[string]any{"8443": map[string]any{"HTTPS": true}}}}}
			case "tcp":
				f.config = map[string]any{"TCP": map[string]any{"8443": map[string]any{"TCPForward": "127.0.0.1:9000"}}}
			}
			if err := f.api().validate(selectedTailscale(t)); err == nil {
				t.Fatal("unsafe forwarding was accepted")
			}
		})
	}
}
func TestTailscaleInstructionsUseInstalledPortsAndUninstallIsManual(t *testing.T) {
	s := selectedTailscale(t)
	s.HTTPPort = 49199
	s.PublicOrigin = "https://nas.example.ts.net:18443"
	root := t.TempDir()
	private := t.TempDir()
	if err := os.Mkdir(filepath.Join(private, "config"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := publishTailscaleInstructions(root, private, s); err != nil {
		t.Fatal(err)
	}
	port, _ := os.ReadFile(filepath.Join(private, "config/tailscale-https-port"))
	if string(port) != "18443\n" {
		t.Fatal("wrong HTTPS port")
	}
	data, _ := os.ReadFile(filepath.Join(root, "TAILSCALE.txt"))
	text := string(data)
	for _, value := range []string{"--bg --https=18443 http://127.0.0.1:49199", "--https=18443 --set-path=/ off", "does not modify Tailscale"} {
		if !strings.Contains(text, value) {
			t.Fatal("missing instruction:", value)
		}
	}
	s.InstallationID, _ = secret()
	s.Phase = "READY"
	s.Release.Schema = 2
	if err := writeJSON(filepath.Join(root, "settings.json"), s, 0600); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := uninstallWizard(&output, root); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output.String(), "--https=18443 --set-path=/ off") || !strings.Contains(output.String(), "does not change Tailscale forwarding automatically") {
		t.Fatal("missing uninstall instructions")
	}
	if !strings.Contains(tailscaleSetupCommand(), "$(cat /var/packages/timekeeper/target/private/config/http-port)") {
		t.Fatal("wizard ignored automatic port selection")
	}
	if err := os.WriteFile(filepath.Join(private, "config/http-port"), []byte("49199\n"), 0644); err != nil {
		t.Fatal(err)
	}
	// Evaluate the offered shell command against fixture files, substituting only
	// printf for the real CLI. No privileged command or Tailscale write executes.
	command := strings.Replace(tailscaleSetupCommand(), tailscaleCLI, "printf '%s\\n'", 1)
	command = strings.ReplaceAll(command, "/var/packages/timekeeper/target/private", private)
	arguments, err := exec.Command("/bin/sh", "-c", command).Output()
	if err != nil || string(arguments) != "serve\n--bg\n--https=18443\nhttp://127.0.0.1:49199\n" {
		t.Fatalf("invalid manual setup command: %s %v", arguments, err)
	}
	// Disabling changes instructions only; no Tailscale write or root command runs.
	s.Tailscale = false
	if err := publishTailscaleInstructions(root, private, s); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "TAILSCALE.txt")); !os.IsNotExist(err) {
		t.Fatal("stale instructions retained")
	}
}
