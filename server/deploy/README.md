# Server deployment

This directory contains Compose configuration, database bootstrap and deployment
scripts. See [deployment instructions](../../docs/operations/DEPLOYMENT.md) and
[local Docker setup](../../docs/operations/LOCAL-DOCKER.md). Both use the
[shared Tailscale access guide](../../docs/operations/TAILSCALE.md) for private HTTPS.

Each API container requires **at least 2 GiB of Java heap**, plus memory outside
the heap. The default is `JAVA_OPTS=-Xms128m -Xmx2g`; environment overrides remain
available. Do not impose a container memory limit equal to the heap size: leave
headroom for native JVM memory and thread stacks, and budget separately for the
database and host. For two API replicas, allocate the heap budget twice.
The entrypoint exits on JVM heap exhaustion so the container restart policy can
recover the service instead of leaving a live, unhealthy JVM.
`TMPDIR=/instance/tmp` puts Java temporary files on the private state volume;
allow disk space for ZIP preparation. The entrypoint creates the directory with
private permissions and passes it as `java.io.tmpdir`.

The optional [Synology logging project](logging/README.md) collects selected
Docker and DSM logs in Grafana independently of the application deployment.
