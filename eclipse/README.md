# Eclipse client

This directory contains the Eclipse plugins, optional Mylyn adapter, features,
p2 repository, target definitions and installed-client test bundles.
Bundle IDs, Java package names and Maven coordinates retain their existing names.

Import the root Maven reactor and select `eclipse/default.target` in PDE.
Run `./mvnw -B -ntp clean verify` from the repository root. Shared Java bundles
live under `common/` and participate in the same Tycho reactor.

See [repository development](../docs/development/REPOSITORY.md),
[remote Eclipse acceptance](../docs/development/REMOTE-ECLIPSE.md) and
[remote Mylyn acceptance](../docs/development/REMOTE-MYLYN.md).
