# Private HTTPS access through Tailscale

This setup works for Timekeeper on a Synology NAS, Docker Desktop or Docker
Engine. [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
provides HTTPS access within your tailnet and forwards requests to the server's
local HTTP endpoint. Install the server using the [Synology deployment guide](DEPLOYMENT.md)
or [local Docker guide](LOCAL-DOCKER.md), then configure access here.

## Prepare the server host

Connect the server host and client device to Tailscale. Enable MagicDNS and HTTPS
certificates in the tailnet; Serve prompts for missing setup. Tailnet access
rules must allow the client device to reach this host on TCP port **8443**.
See [HTTPS setup](https://tailscale.com/docs/how-to/set-up-https-certificates).

Run the commands on the host operating system, where `http://127.0.0.1:8080`
is reachable. Keep `TIMEKEEPER_BIND_ADDRESS=127.0.0.1` in the deployment `.env`.
Both Compose setups already publish their gateway on this address. If you changed
`TIMEKEEPER_HTTP_PORT`, replace `8080` in the commands below.

On **Synology**, install and sign in to the Tailscale package through Package
Center, then open an SSH shell on the NAS. If the CLI is unavailable in your
shell, use its package path with administrator privileges:

```sh
alias tailscale='sudo /var/packages/Tailscale/target/bin/tailscale'
```

See the [official Synology instructions](https://tailscale.com/docs/integrations/synology)
for package installation and SSH access.

On **macOS**, install CLI integration from the Tailscale app settings, or use
its bundled CLI in this shell:

```sh
alias tailscale='/Applications/Tailscale.app/Contents/MacOS/Tailscale'
```

See the [macOS CLI instructions](https://tailscale.com/docs/reference/tailscale-cli?tab=macos).
On **Windows with Docker Desktop**, run Serve on Windows. On **Linux**, use the
installed `tailscale` CLI on the Docker host.

Check that the local server is ready:

```sh
curl --fail --silent --show-error http://127.0.0.1:8080/q/health/ready
```

## Enable private HTTPS access

Check existing endpoints, then enable the Timekeeper endpoint:

```sh
tailscale status
tailscale serve status
tailscale serve --bg --https=8443 http://127.0.0.1:8080
tailscale serve status
```

Choose another HTTPS port if 8443 already serves another application, and allow
that port in the tailnet's access rules. `--bg` keeps the endpoint available
after the command exits and resumes it after Tailscale restarts. The host must
remain running and connected. See the [Serve CLI reference](https://tailscale.com/docs/reference/tailscale-cli/serve).

Use the full HTTPS DNS name printed by Serve. For example:

| Client | Address |
| --- | --- |
| Browser | `https://timekeeper.tail1234.ts.net:8443/web/` |
| Eclipse Server URL | `https://timekeeper.tail1234.ts.net:8443/api/v1` |
| Readiness | `https://timekeeper.tail1234.ts.net:8443/q/health/ready` |

Replace the example hostname with your host's printed name. Use that DNS name
for certificate validation; Eclipse requires HTTPS outside loopback. Browser
sessions use normal account sign-in; Eclipse uses a personal API token created
under **Account**. Set `TIMEKEEPER_PUBLIC_ORIGIN` to the exact HTTPS origin
(including the port when non-default) and recreate both server replicas. This
allows browser Origin validation behind Tailscale Serve. Both use the same
private account and persistent database as local sessions. See the
[account guide](ACCOUNTS.md).

## Verify and remove the endpoint

From the client device, verify readiness:

```sh
curl --fail --silent --show-error https://timekeeper.tail1234.ts.net:8443/q/health/ready
```

If this fails, check local server readiness first, then Serve status, the
Tailscale connection, DNS and access rules for the selected HTTPS port.
To remove this endpoint, run on the server host:

```sh
tailscale serve --bg --https=8443 off
```
