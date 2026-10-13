# Timekeeper server

The Quarkus server exposes API v1 and serves the bundled browser client at `/web/`.
The frontend source and browser tests live in `web/`. Build from the repository
root with `./mvnw -B -ntp -f server/pom.xml clean verify`.

- [Server configuration and behavior](../docs/operations/SERVER.md)
- [Local Docker setup](../docs/operations/LOCAL-DOCKER.md)
- [Deployment, backup and rolling updates](../docs/operations/DEPLOYMENT.md)
- [Web client development](../docs/development/WEB-CLIENT.md)
- [API v1 contract](../docs/architecture/API-V1.md)
