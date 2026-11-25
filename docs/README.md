# Dagsverk documentation

Dagsverk tracks work sessions and activities in a browser or Eclipse. Start with
the guide for your task; implementation contracts and dated test reports are
separate references.

## End users

- [Desktop browser](user/DESKTOP.md)
- [Mobile browser](user/MOBILE.md)
- [Eclipse](user/eclipse/README.md)
- [Account and data guides](user/README.md#account-and-data)

## Server administrators

Start with the [server administration guides](operations/README.md) for
deployment, accounts, configuration and recovery.

## Developers

Start with [repository setup and build commands](development/REPOSITORY.md).
The [development index](development/README.md) links component guides, browser and
installed-client acceptance, packaging and release procedures.

[Architecture](architecture/README.md) describes component boundaries and links
the data model, API contract, Notes storage, task synchronization and backup
implementation. These are detailed references, not installation steps.

## Historical evidence

[Verification reports](verification/README.md) record results for specific source
commits and environments. They do not establish that the current checkout passes.
The [upgrade plan](development/UPGRADE-PLAN.md) preserves dated decisions;
current commands belong in the development guides.

New product copy uses Dagsverk. Existing package IDs, Java namespaces, API headers,
configuration aliases and storage identifiers retain `timekeeper` where required
for compatibility; see [the naming transition](development/BRANDING.md).

## Upgrade and rollback

Back up before changing a database or runtime. Embedded Eclipse and standalone
server storage have different schemas and upgrade procedures. Use
[embedded recovery](operations/DATABASE-RECOVERY.md),
[server/account upgrades](operations/ACCOUNTS.md#existing-data-and-accounts) or
[instance recovery](operations/INSTANCE-BACKUP.md) for the relevant deployment.
A binary downgrade across an incompatible schema needs its matching backup;
later work is not merged automatically.
