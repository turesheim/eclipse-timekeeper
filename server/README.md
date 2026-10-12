# Timekeeper Quarkus server

Work in progress for #218, based on the [v1 API contract](../api/README.md).
The server is required before 2.0 release, together with remote Eclipse, web
and synchronization acceptance. This bootstrap checkpoint is not a completed
server deployment.

The standalone Maven reactor compiles the canonical `timekeeper-domain` and
`timekeeper-service-api` sources through two ordinary JAR modules. There is no
copied core implementation, Tycho extension, target-platform resolution or
Eclipse Platform/Mylyn/OSGi runtime dependency in this reactor. The Eclipse
packaging of the same sources remains in the root Tycho build.

The app uses the published Quarkus platform **3.40.1 LTS**, Java 21 and
H2 **2.5.250**. It serves the canonical public OpenAPI document and real
readiness/datasource health endpoints. Production database configuration requires
an explicit `TIMEKEEPER_SERVER_DB_URL`; tests use their own in-memory database.
It does not open or initialize the embedded Eclipse database implicitly.

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
```

The bootstrap tests start the real Quarkus runtime, query H2 through the injected
Agroal datasource, check readiness and fetch the canonical OpenAPI document over
HTTP. The existing pure domain/service tests also run in this reactor.

Still required before #218 can close:

- Owner/token authentication, owner-bound repository adapters and all v1 operations.
- Guarded server schema initialization/versioning, transactions and optimistic writes.
- Durable idempotency receipts, atomic tracking and committed change storage.
- Full black-box contract suite, restart/concurrent-process/schema-safety tests,
  operational documentation and release packaging.

No CRUD, remote-client or multi-user acceptance is claimed by the bootstrap tests.
