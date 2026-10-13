# Shared code and contracts

- `timekeeper-domain/`: immutable Java domain records and identifiers.
- `timekeeper-service-api/`: application service ports and their shared implementation.
- `timekeeper-http-client/`: Java HTTP client used by Eclipse and server acceptance tests.
- `api/`: canonical OpenAPI v1 contract and HTTP contract tests.

These Java sources have no Eclipse Platform dependency. The root Tycho reactor
packages them as OSGi bundles; `server/core-*` builds the same sources for the
standalone server. See [repository development](../docs/development/REPOSITORY.md),
[API v1](../docs/architecture/API-V1.md) and
[HTTP client documentation](../docs/development/HTTP-CLIENT.md).
