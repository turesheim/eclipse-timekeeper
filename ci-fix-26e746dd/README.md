# PR #641 CI corrections

Tested source: `26e746ddc7b6d3e6bf458651e467fd8470b7fbd4`, including main `1cd0cc96`.

The client-note test resolves the note by ID and checks its filename title. The replica corpus gives the priority document a matching filename and a different first heading, then checks both its rank and filename-derived title.

- [Quality receipt](quality.json): quick, contract, web and server groups passed with `python3 scripts/quality.py --affected --base e63a453b4eb2dc04b47f3c1c6e49aa4ee0d84b88`.
- [PostgreSQL 17.11](postgres-17.json) and [PostgreSQL 18.6](postgres-18.json): full real two-JVM replica scenarios passed.
- [Browser summary](verification.json): 10 Chromium client-note cases, 10 WebKit/HTTPS client-note cases and the original Obsidian import case passed.
- All captures use synthetic documents and accounts. Browser traces and credentials are excluded.

## Current captures

[Obsidian desktop](chromium-obsidian/obsidian-formatted-import.png) · [Obsidian mobile](chromium-obsidian/obsidian-formatted-import-mobile.png)

[Client metadata, desktop](chromium-clients/note-client-label-1440.png) · [Client metadata, mobile](chromium-clients/note-client-label-320.png)

[WebKit metadata, desktop](webkit-clients/note-client-label-1440.png) · [WebKit metadata, mobile](webkit-clients/note-client-label-320.png)

## Calendar timing investigation

A separate native-touch calendar scenario, unchanged by this PR, once selected 16:15 instead of the expected 16:30 in CI. The same scenario passed in the previous CI run, [12 consecutive isolated local repetitions](calendar-repeat.json), and the [complete local CI browser shard: 176 cases, zero failures/errors/skips](browser-shard3.json). The original exact-time assertions remain unchanged. [Build #1252](https://github.com/turesheim/eclipse-timekeeper/actions/runs/43007187590) records the CI rerun.
