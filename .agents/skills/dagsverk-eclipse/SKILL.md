---
name: dagsverk-eclipse
description: Change Dagsverk Eclipse plugins, SWT views, Mylyn integration, local/remote backends or Tycho packaging. Use for Eclipse implementation and installed-client verification.
---

# Dagsverk Eclipse client

Read root-relative `eclipse/README.md` and
`docs/development/REPOSITORY.md`. Consult `docs/development/REMOTE-ECLIPSE.md`
for remote-client changes and `REMOTE-MYLYN.md` for Mylyn changes.

- Keep bundle IDs, Java package names, extension IDs, preferences and Maven
  coordinates stable. `docs/development/BRANDING.md` describes the naming phases.
- Keep SWT access on the UI thread. Run network/database operations in existing
  background Jobs and handle disposal/cancellation consistently with nearby code.
- Preserve both local JPA/H2 and server HTTP paths. Selecting a backend does not
  copy records between databases. Change shared behavior in `common/`.
- Treat Mylyn as optional. Follow the current target platform in
  `eclipse/default.target`, retaining feature separation and existing acceptance
  exceptions documented for the current release.
- Use Java 21 and `./mvnw`; root builds use Tycho and may need a graphical
  session/Xvfb. Shared changes also require the independent server reactor.
- Choose focused unit/UI tests first. For remote state, Mylyn tracking or
  lifecycle changes, use the documented installed-client scenarios, including
  retained-workspace restart where relevant. State when display/native/runtime
  prerequisites prevent verification; compilation alone is not installed acceptance.
