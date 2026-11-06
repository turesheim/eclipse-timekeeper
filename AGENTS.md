# Dagsverk

Dagsverk (formerly Timekeeper) tracks work sessions and activities through a
bundled web client and Eclipse plugins. Use Dagsverk in new product copy and
project skills. Existing Java packages, bundle IDs, Maven coordinates, API
headers, environment variables, archive formats, URLs, theme IDs and storage
keys remain `timekeeper` for compatibility. See
[the naming transition](docs/development/BRANDING.md).

## Sources of truth

- [Architecture](docs/architecture/README.md) and [API v1](docs/architecture/API-V1.md).
- [Build/test commands](docs/development/REPOSITORY.md),
  [web development](docs/development/WEB-CLIENT.md) and
  [agent workflow](docs/development/AGENT-WORKFLOW.md).
- `common/` contains the canonical shared Java sources. Both the root Tycho
  reactor and `server/pom.xml` compile them. Keep domain/services independent
  of Eclipse, HTTP and persistence frameworks. Never fix a shared defect by
  copying the implementation into a client or server adapter.
- `docs/verification/` contains dated evidence; it does not prove the current
  checkout passes. Current code, contracts and fresh results take precedence.

## Working agreements

- Preserve existing local changes. Keep the diff within the user's task.
- Derive observable acceptance criteria from the request and existing behavior.
  Resolve routine choices autonomously; ask only when a missing decision
  materially affects the outcome. Scale planning to the size of the change.
- Use the relevant `dagsverk-*` skills for implementation or requested reviews.
  Keep reusable instructions concise and grounded in demonstrated needs.
- Follow the pinned stack: Java 21, Python 3.13, Node 24 and the Maven wrapper.
  The web client uses native browser modules and Bootstrap **5.3.8**. The
  installed Bootstrap 6 skills are disabled for this project; using them
  requires an explicitly scoped migration. Preserve the existing bundling.
- Respect account isolation, account time zones, optimistic concurrency and
  local/remote storage boundaries. Add regression coverage for changed behavior
  or a reproduced defect; do not add tests that merely mirror the implementation.
- Run `python3 scripts/quality.py --affected` before delivering code changes.
  Use `--plan` first when useful. Add scenario/browser/installed-client checks
  when the behavior requires them, following the component guides.
  Scope with `--paths <file> ...` when unrelated pre-existing work would select
  additional components; never exclude files changed for the current task.
- Report the resulting behavior, checks actually run, and remaining limitations.
  A quick syntax check, skipped test or unavailable environment is not a passing
  behavioral test. Match evidence to the current source state.

## Scope

Local hooks assist the agent. CI remains the merge gate. Implementation requests
authorize local edits and relevant checks, not releases, deployment or messages
to other people. Follow the user's current scope and existing authorization.
