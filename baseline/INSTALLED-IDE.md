# Installed-IDE acceptance

Verified September 21, 2026, after PR #196 merged (`0cbf453`). Historical
migrations remain out of scope. All records and workspaces used here were
synthetic; no personal Timekeeper database was opened.

## Environment and artifacts

- Eclipse Platform Runtime 4.41, build `R-4.41-202608281142`, macOS Cocoa aarch64.
  This was a pristine Platform installation, not the Tycho test application or
  a preconfigured IDE package.
- Temurin Java 21, Maven 3.9.16, the repository's dated 2026-09 target.
- [Official runtime download](https://download.eclipse.org/eclipse/downloads/drops4/R-4.41-202608281142/eclipse-platform-4.41-macosx-cocoa-aarch64.dmg).
  SHA-512 matched the release's `buildproperties.json`:
  `5a35d58899a1400c8b0447c0e3a34b8124637c7c0da78326c2a1dac212ff9d61e0e80b307b9dd0c2a4ae1ae2cc9dacf526d7639267f812759b043e534c9737a1`.
- Clean build: `mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore`.
  All six reactor projects passed: 62 database/report/packaging tests and
  8 UI/integration tests passed; one existing CSV-export test remained ignored.
- Verified p2 ZIP:
  `net.resheim.eclipse.timekeeper-site/target/net.resheim.eclipse.timekeeper-2.0.0.202609211009.zip`.
  This is a verification artifact, not a published release.

Local evidence is under `/private/tmp/timekeeper-installed.RaDTib` (installation
and runtime logs, three SQL snapshots, isolated applications and data). The
clean source/build is `/private/tmp/timekeeper-packaging.XhVDPr`, with build log
`/private/tmp/timekeeper-packaging-final.log`. These temporary files are not
committed and may be removed by the operating system.

## Packaging corrections

The first clean installation resolved bundles but omitted Mylyn's Tasks
search/index contributions. Opening Task List logged:
`No search provider was registered. Tasks search is not available.`
Timekeeper now requires the complete `org.eclipse.mylyn.tasks.feature`, version
4.12.0 with compatible matching. Bugzilla remains an optional separate connector.

The same installation contained both the host's `slf4j.simple` and Timekeeper's
explicitly included `org.eclipse.equinox.slf4j`. Removing the latter from the
shipping feature avoids installing a second SLF4J provider. Timekeeper retains
the SLF4J API and uses the host's logging provider. The test target's logging
configuration is separate from the shipping feature.

Two metadata regression tests check these requirements. They do not replace
actual p2 resolution or runtime testing.

## Installation and restart results

1. Installed the PR #196 baseline (`2.0.0.202609210953`) into a pristine runtime
   using p2 director and the dated SimRel repository. Installation succeeded.
2. Opened Workweek and Mylyn Task List; created a local task named
   `Synthetic installed acceptance`, activated it and deactivated it through
   Workweek. Inspected Timekeeper's database and label preferences without
   changing settings. The database page has no historical recovery actions.
3. Closed Eclipse and exported a read-only SQL snapshot. Schema version 1 was
   READY with origin NEW. Task 1 had one completed activity, no current activity,
   and seven default labels existed. The activity ran from
   `2026-09-21 12:04:18.181108` to `2026-09-21 12:06:00.993865`;
   H2 `DATEDIFF('SECOND', ...)` returned 102. The minute-formatted Workweek total
   was `0:01` on Monday September 21.
4. Built the corrected package and installed it into a second pristine runtime.
   Also replaced the original installed feature with
   `2.0.0.202609211009` in one p2 uninstall/install transaction. Both succeeded.
   Both installations contained Mylyn Tasks search/index bundles and only the
   host's SLF4J provider, not `org.eclipse.equinox.slf4j`.
5. Restarted the updated installation with the original synthetic workspace and
   database. Workweek retained the task, completed activity and total. The
   missing-search and multiple-provider warnings were gone. A full SQL snapshot
   comparison found only `TASK_URL` changing from NULL to an empty string when
   the local Mylyn task was relinked. IDs, timestamps, current-activity state,
   labels and other stored data were unchanged; this is not an exact SQL match.
6. Opened the independently pristine corrected installation with a new empty
   Mylyn workspace against the same database, sequentially after closing the
   first installation. Workweek still displayed the stored task, activity and
   `0:01` total without the original Mylyn task list. Closed the IDE and exported
   SQL again: `after-clean.sql` exactly matched `after-update.sql` with `cmp`.

Every GUI launch explicitly supplied a temporary workspace, temporary Java
`user.home`, Java 21 and
`-Dnet.resheim.eclipse.timekeeper.db.url=jdbc:h2:/private/tmp/timekeeper-installed.RaDTib/data/timekeeper`.
The database URL override acts before normal storage preference resolution;
these checks therefore do not validate preference-selected storage modes.

## Repeating the checks safely

Use disposable copies of the runtime and separate temporary workspace/home/data
directories. Never point this procedure at an unsupported or personal database.
Build from clean sources, then use the
[p2 director](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/p2_director.html)
with these arguments, substituting absolute temporary paths:

```text
<app>/Contents/MacOS/eclipse
  -nosplash -consoleLog -vm <jdk21>/bin/java
  -data <temporary-director-workspace>
  -application org.eclipse.equinox.p2.director
  -repository file:<absolute-built-repository>,https://download.eclipse.org/releases/2026-09/202609091000/
  -installIU net.resheim.eclipse.timekeeper.feature.group/<built-version>
  -vmargs -Duser.home=<temporary-home> -Declipse.p2.mirrors=false
  -Dnet.resheim.eclipse.timekeeper.db.url=jdbc:h2:mem:provisioning
```

For the update transaction, additionally provide
`-uninstallIU net.resheim.eclipse.timekeeper.feature.group/<installed-version>`.
For the GUI launch omit the director application/repository/install arguments,
use a separate GUI workspace and replace the in-memory URL with an explicit
temporary file database URL. Close Eclipse before taking SQL snapshots using
H2's `org.h2.tools.Script`, with `IFEXISTS=TRUE;ACCESS_MODE_DATA=r` on the URL and
`NOPASSWORDS NOSETTINGS` export options. Preserve the first snapshot for comparison.

## Remaining issues and limits

- Opening Task List in the updated real installation still logs an upstream
  `org.eclipse.mylyn.commons.ui` theme error:
  `NumberFormatException: For input string: "or" under radix 16`, from
  `E4ThemeColor.getRGBFromCssString`, through `GradientColors` and
  `TaskListToolTip`. Inspection of the selected Mylyn source shows the parser
  treats an unrecognized symbolic CSS color as hex. It logs the failure and
  returns null. Workweek remains usable, but clean dependency-error acceptance
  is not complete. No upstream fix has been included here.
- EclipseLink still warns about a password encrypted with a deprecated algorithm
  with the synthetic database's `sa`/empty-password configuration.
- Test-harness Cocoa/menu/theme messages were not reproduced as those same
  errors in the installed IDE. Passing tests alone does not settle runtime logs.
- This verifies embedded storage selected by the test override, normal shutdown
  and sequential restart/update. Preference switching, interrupted activities,
  multiple installed instances, server configurations, remote connectors,
  manual editing, CSV import/export and OS idle detection remain unverified.
- macOS Apple Silicon was exercised here. Linux CI is separate evidence;
  Windows and other desktop/runtime combinations are not certified by this run.

The clean install/update portions of step 6 are verified. The upgrade is not
ready for publication while the remaining runtime and platform checks are open.
