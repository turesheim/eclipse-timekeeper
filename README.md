# Timekeeper for Eclipse <a href="https://github.com/turesheim/eclipse-timekeeper/actions?query=workflow%3ABuild"><img src="https://github.com/turesheim/eclipse-timekeeper/workflows/Build/badge.svg"/>

This is a simple time-tracking plug-in integrating with [Eclipse Mylyn](http://eclipse.org/mylyn/) Tasks.

![image](https://github.com/turesheim/eclipse-timekeeper/raw/master/resources/screenshots/workweek-view.png)

Whenever a task is *activated* in Mylyn it will automatically show up in the **Workweek** view with a bold label, and the amount of time the task is active will be tracked. An *activity* will be added to the task, which is the entity keeping track of the time and a short note. Multiple activities can be added to each task.

When the task is *deactivated* the end time is registered on the activity and the active time is added to the toal for the task on the particular day. It is also possible to manually edit the start and stop times by clicking into the cell.

The context menu and toolbar buttons can be used to browse back and forward by one week. The current locale is used to determine week numbers. Left of the navigation buttons there is a button for copying and exporting the displayed workweek in various formats. The export definitions can be modified or new ones can be added using [Freemarker](https://freemarker.apache.org) templates found in the preference settings. 

See the <a href="../../wiki">wiki</a>  for more about usage.

The data is stored in an H2 SQL database, mapped to POJOs using the Java Persistence API with EclipseLink. Reports are generated using Apache FreeMarker. The database has an explicit schema version and initialization state; historical data migration is not supported.

## Database configuration

The Database configuration page in preferences (**Timekeeper > Database**) allows you to configure where the database for the running Eclipse instance should be kept. The default is to place it in the shared location, under `.timekeeper` in your home folder. But you can also use a workspace relative path, or even a H2 server if you have one running.

<img src="https://github.com/turesheim/eclipse-timekeeper/raw/master/resources/screenshots/preferences-database.png" width="50%"/>

Multiple instances of the Timekeeper can share the database as it utilizes a H2 feature called mixed mode. This will automatically start a server instance on port 9090 if more connections are needed.

The Eclipse 2026-09 upgrade uses **H2 2.5.250 with schema version 1** as its
supported database baseline. Start with new storage; historical H2 files,
unversioned databases and old Mylyn attribute records are not imported.
Schema versioning and guarded migration-target infrastructure are retained for
future migrations, but there are no active migration recipes or recovery buttons.
See the [database policy, backups and future migration contract](DATABASE-RECOVERY.md).

Keep unsupported databases intact and select a separate empty location. CSV
Export/Import now targets the current `TASK`, `ACTIVITY` and `TASK_ACTIVITY`
schema, but it remains a convenience interchange format rather than a database
backup, migration or rollback mechanism.

## Installing

You can install the latest **public release** from the <a href="http://marketplace.eclipse.org/content/timekeeper-eclipse">Eclipse Marketplace</a> or drag <a href="http://marketplace.eclipse.org/marketplace-client-intro?mpc_install=2196325" title="Drag and drop into a running Eclipse workspace to install Eclipse Timekeeper"><img src="https://marketplace.eclipse.org/sites/all/themes/solstice/public/images/marketplace/btn-install.png" height="28px"/>
</a> into an running Eclipse instance. The latest CI build artifacts can be found under [GitHub Actions](https://github.com/turesheim/eclipse-timekeeper/actions?query=workflow%3ABuild). In order to install from there you must download the _p2-repository_ zip file and point your Eclipse instance to that. 

The Eclipse 2026-09 upgrade has been installed and updated in a clean Eclipse
4.41 runtime on macOS Apple Silicon with Java 21. Installation requires the
complete Mylyn Tasks feature; enable the Eclipse 2026-09 software site so p2 can
resolve it. Additional repository connectors such as Bugzilla are optional.
See the [installed-IDE acceptance report](baseline/INSTALLED-IDE.md) for verified
behavior and remaining runtime issues. This is not yet a release certification.

## Building

Use JDK 21 and Maven 3.9.9 or newer. Clone the project and from the root execute:

    mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore

When the build completes successfully there will be a Eclipse p2 repository at *net.resheim.eclipse.timekeeper-site/target/repository* which you can install from.

For Eclipse PDE development, open `default.target` and choose **Set as Active
Target Platform**. This is the authoritative target for both PDE and Maven,
using the dated Eclipse 2026-09 repository with pinned root dependencies.
Configure a JavaSE-21 execution environment in Eclipse. The development and
integration-test launch configurations use this environment and standard PDE
launchers; Java Mission Control is not required.

On Linux, run the UI tests under a graphical session or Xvfb, as in the GitHub
Actions workflow. Upgrade progress, known failures and migration requirements
are tracked in [UPGRADE-PLAN.md](UPGRADE-PLAN.md) and [baseline/README.md](baseline/README.md).

## Note

The Eclipse 2026-09 upgrade targets Java 21. Current-baseline storage and runtime
compatibility must be verified before publishing the upgraded plugin.

This project is using [JProfiler](https://www.ej-technologies.com/products/jprofiler/overview.html) for debugging performance issues.

## License

Copyright © 2014-2020 Torkild Ulvøy Resheim. All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
