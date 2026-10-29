package main

// Integration is read-only: administrators configure the existing NAS daemon
// themselves. No sudo, root helper, separate daemon or Serve mutations are used.
import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const tailscaleSocket = "/var/packages/Tailscale/var/tailscaled.sock"
const tailscaleCLI = "/var/packages/Tailscale/target/bin/tailscale"

type tailscaleAPI struct{ client *http.Client }

func localTailscale() tailscaleAPI {
	return tailscaleAPI{&http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", tailscaleSocket)
	}}}}
}
func (a tailscaleAPI) get(path string) ([]byte, error) {
	response, err := a.client.Get("http://local-tailscaled.sock/localapi/v0/" + path)
	if err != nil {
		return nil, errors.New("Cannot reach Tailscale; install and start its DSM package, then sign in")
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 2*1024*1024+1))
	if err != nil || len(data) > 2*1024*1024 || response.StatusCode != 200 {
		return nil, errors.New("Cannot read Tailscale status; check its DSM package")
	}
	return data, nil
}
func (a tailscaleAPI) hostname() (string, error) {
	data, err := a.get("status?peers=false")
	if err != nil {
		return "", err
	}
	var status struct {
		BackendState   string
		CurrentTailnet *struct{ MagicDNSEnabled bool }
		Self           *struct {
			DNSName      string
			Capabilities []string
			CapMap       map[string]json.RawMessage
		}
	}
	if json.Unmarshal(data, &status) != nil {
		return "", errors.New("Cannot read Tailscale status")
	}
	if status.BackendState != "Running" || status.Self == nil {
		return "", errors.New("Start Tailscale and sign this NAS into your tailnet before selecting integration")
	}
	if status.CurrentTailnet != nil && !status.CurrentTailnet.MagicDNSEnabled {
		return "", errors.New("Enable MagicDNS in Tailscale before selecting integration")
	}
	host := strings.ToLower(strings.TrimSuffix(status.Self.DNSName, "."))
	if !strings.HasSuffix(host, ".ts.net") || origin("https://"+host) != nil {
		return "", errors.New("Enable MagicDNS for this NAS in Tailscale before selecting integration")
	}
	https := false
	for _, c := range status.Self.Capabilities {
		if c == "https" {
			https = true
		}
	}
	if _, ok := status.Self.CapMap["https"]; ok {
		https = true
	}
	if !https {
		return "", errors.New("Enable HTTPS certificates in the Tailscale admin console before selecting integration")
	}
	return host, nil
}
func configureAccess(s Settings, env func(string) string) (Settings, error) {
	return configureAccessWith(s, env, localTailscale().hostname)
}
func configureAccessWith(s Settings, env func(string) string, detect func() (string, error)) (Settings, error) {
	flag := env("wizard_tailscale")
	if flag == "" {
		return s, nil
	} // Upgrades without wizard input preserve existing access.
	if flag != "true" && flag != "false" {
		return s, errors.New("Invalid Tailscale integration selection")
	}
	s.Tailscale = flag == "true"
	if s.Tailscale {
		host, err := detect()
		if err != nil {
			return s, err
		}
		port := env("wizard_tailscale_port")
		if port == "" {
			port = "8443"
		}
		n, err := strconv.Atoi(port)
		if err != nil || n < 1024 || n > 65535 {
			return s, errors.New("Use a Tailscale HTTPS port between 1024 and 65535")
		}
		s.PublicOrigin = "https://" + net.JoinHostPort(host, strconv.Itoa(n))
	} else if manual := env("wizard_public_origin"); manual != "" {
		s.PublicOrigin = manual
	}
	return s, origin(s.PublicOrigin)
}
func tailscalePort(s Settings) (int, error) {
	if err := origin(s.PublicOrigin); err != nil {
		return 0, err
	}
	u, _ := url.Parse(s.PublicOrigin)
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1024 || port > 65535 || !strings.HasSuffix(u.Hostname(), ".ts.net") {
		return 0, errors.New("Tailscale integration needs the NAS's HTTPS hostname and an explicit port, normally 8443")
	}
	if port == s.HTTPPort || port == s.DatabasePort {
		return 0, errors.New("Tailscale HTTPS, local HTTP and PostgreSQL must use different ports")
	}
	return port, nil
}
func (a tailscaleAPI) validate(s Settings) error {
	if !s.Tailscale {
		return nil
	}
	host, err := a.hostname()
	if err != nil {
		return err
	}
	u, _ := url.Parse(s.PublicOrigin)
	if u == nil || u.Hostname() != host {
		return errors.New("The public HTTPS origin must match this NAS's detected Tailscale hostname")
	}
	port, err := tailscalePort(s)
	if err != nil {
		return err
	}
	data, err := a.get("serve-config")
	if err != nil {
		return err
	}
	var config map[string]any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if decoder.Decode(&config) != nil {
		return errors.New("Cannot read Tailscale forwarding configuration")
	}
	// Permit an existing private Timekeeper mapping. Refuse a different service
	// so the manual command offered by the wizard cannot silently replace it.
	return checkManualForwarding(config, host, port, s.HTTPPort)
}
func section(parent map[string]any, key string) (map[string]any, error) {
	if parent[key] == nil {
		return map[string]any{}, nil
	}
	value, ok := parent[key].(map[string]any)
	if !ok {
		return nil, errors.New("Unrecognized Tailscale forwarding configuration; inspect Serve manually")
	}
	return value, nil
}
func listenerInUse(config map[string]any, port string) bool {
	for key, value := range config {
		if key == "TCP" {
			if tcp, ok := value.(map[string]any); ok && tcp[port] != nil {
				return true
			}
		}
		if key == "Web" {
			if web, ok := value.(map[string]any); ok {
				for host := range web {
					if strings.HasSuffix(host, ":"+port) {
						return true
					}
				}
			}
		}
		if child, ok := value.(map[string]any); ok && listenerInUse(child, port) {
			return true
		}
	}
	return false
}
func checkManualForwarding(config map[string]any, host string, httpsPort, httpPort int) error {
	port := strconv.Itoa(httpsPort)
	key := net.JoinHostPort(host, port)
	funnel, err := section(config, "AllowFunnel")
	if err != nil {
		return err
	}
	if value := funnel[key]; value != nil && value != false {
		return errors.New("Funnel is enabled for this address; choose a private Tailscale Serve port")
	}
	foreground, err := section(config, "Foreground")
	if err != nil {
		return err
	}
	if listenerInUse(foreground, port) {
		return errors.New("This HTTPS port has a foreground Serve session; choose another port or configure persistent forwarding manually")
	}
	tcp, err := section(config, "TCP")
	if err != nil {
		return err
	}
	web, err := section(config, "Web")
	if err != nil {
		return err
	}
	if !listenerInUse(config, port) {
		return nil
	}
	listener, err := section(tcp, port)
	if err != nil {
		return err
	}
	site, err := section(web, key)
	if err != nil {
		return err
	}
	handlers, err := section(site, "Handlers")
	if err != nil {
		return err
	}
	handler, err := section(handlers, "/")
	if err != nil {
		return err
	}
	if httpPort == 0 {
		httpPort = 8080
	} // Preferred port before a fresh installation.
	expected := fmt.Sprintf("http://127.0.0.1:%d", httpPort)
	if len(listener) == 1 && listener["HTTPS"] == true && len(handler) == 1 && handler["Proxy"] == expected {
		return nil
	}
	return errors.New("This Tailscale HTTPS port already forwards another service; choose a free port or correct forwarding manually as root")
}
func tailscaleSetupCommand() string {
	// Both files contain validated decimal ports. Reading them after installation
	// handles automatic HTTP-port selection and edits to the HTTPS port field.
	return tailscaleCLI + ` serve --bg --https="$(cat ` + packageTarget() + `/private/config/tailscale-https-port)" "http://127.0.0.1:$(cat ` + packageTarget() + `/private/config/http-port)"`
}
func tailscaleRemoveCommand(port int) string {
	return fmt.Sprintf("%s serve --bg --https=%d --set-path=/ off", tailscaleCLI, port)
}
func tailscaleFields(s Settings) []any {
	host, err := localTailscale().hostname()
	status := "Tailscale detected: " + host + "."
	if err != nil {
		status = err.Error() + ". You can continue with manual HTTPS access."
	}
	port := "8443"
	if s.Tailscale {
		if n, err := tailscalePort(s); err == nil {
			port = strconv.Itoa(n)
		}
	}
	return []any{
		map[string]any{"desc": status},
		map[string]any{"type": "multiselect", "desc": "", "subitems": []any{map[string]any{"key": "wizard_tailscale", "desc": "Tailscale integration (manual forwarding as root)", "defaultValue": s.Tailscale}}},
		field("textfield", "wizard_tailscale_port", "Tailscale HTTPS port", port, true),
		map[string]any{"desc": "If you select Tailscale integration, after installation run the following command in DSM Task Scheduler as root, or in a root SSH session: " + tailscaleSetupCommand() + ". This uses the ports selected by the installer. Check 'tailscale serve status' first; do not replace another application's forwarding. Before uninstalling, remove the Timekeeper rule manually as root; the uninstall wizard shows the command. Timekeeper does not request root access or change Tailscale configuration."},
		field("textfield", "wizard_public_origin", "Manual HTTPS origin (when Tailscale integration is off)", s.PublicOrigin, false),
	}
}
func publishTailscaleInstructions(root, private string, s Settings) error {
	path := filepath.Join(private, "config/tailscale-https-port")
	instructions := filepath.Join(root, "TAILSCALE.txt")
	if !s.Tailscale {
		for _, file := range []string{path, instructions} {
			if err := os.Remove(file); err != nil && !errors.Is(err, os.ErrNotExist) {
				return err
			}
		}
		return nil
	}
	port, err := tailscalePort(s)
	if err != nil {
		return err
	}
	if err = atomic(path, []byte(strconv.Itoa(port)+"\n"), 0644); err != nil {
		return err
	}
	text := fmt.Sprintf("Run as root in DSM Task Scheduler or a root SSH session. Inspect existing forwarding first:\n%s serve status\n\nConfigure persistent Timekeeper forwarding:\n%s serve --bg --https=%d http://127.0.0.1:%d\n\nBefore uninstalling, inspect Serve and remove the root handler only if it still forwards Timekeeper:\n%s\n\nTimekeeper does not modify Tailscale. Disabling integration does not remove forwarding.\n", tailscaleCLI, tailscaleCLI, port, s.HTTPPort, tailscaleRemoveCommand(port))
	return atomic(instructions, []byte(text), 0600)
}
func uninstallWizard(output io.Writer, root string) error {
	items := []any{map[string]any{"desc": "The database, credentials, backups and settings are retained. Uninstall removes Timekeeper's container and project."}}
	if s, err := loadSettings(root); err == nil && s.Tailscale {
		port, err := tailscalePort(s)
		if err != nil {
			return err
		}
		items = append(items, map[string]any{"desc": "Before continuing, run the following manually as root in DSM Task Scheduler or a root SSH session. Inspect Serve first and remove the root handler only if it still forwards Timekeeper; other applications may use this listener: " + tailscaleCLI + " serve status; " + tailscaleRemoveCommand(port) + ". Uninstall does not change Tailscale forwarding automatically."})
	}
	return json.NewEncoder(output).Encode([]any{map[string]any{"step_title": "Uninstall Timekeeper", "items": items}})
}
