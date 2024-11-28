# Timekeeper for Eclipse <a href="https://github.com/turesheim/eclipse-timekeeper/actions?query=workflow%3ABuild"><img src="https://github.com/turesheim/eclipse-timekeeper/workflows/Build/badge.svg"/>

This is a simple time-tracking plug-in integrating with [Eclipse Mylyn](http://eclipse.org/mylyn/) Tasks.

![image](https://github.com/turesheim/eclipse-timekeeper/raw/master/resources/screenshots/workweek-view.png)
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fturesheim%2Feclipse-timekeeper.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2Fturesheim%2Feclipse-timekeeper?ref=badge_shield)

Whenever a task is *activated* in Mylyn it will automatically show up in the **Workweek** view with a bold label, and the amount of time the task is active will be tracked. An *activity* will be added to the task, which is the entity keeping track of the time and a short note. Multiple activities can be added to each task.

When the task is *deactivated* the end time is registered on the activity and the active time is added to the toal for the task on the particular day. It is also possible to manually edit the start and stop times by clicking into the cell.

The context menu and toolbar buttons can be used to browse back and forward by one week. The current locale is used to determine week numbers. Left of the navigation buttons there is a button for copying and exporting the displayed workweek in various formats. The export definitions can be modified or new ones can be added using [Freemarker](https://freemarker.apache.org) templates found in the preference settings. 

See the <a href="../../wiki">wiki</a>  for more about usage.

The data is now stored in a H2 SQL database, mapped to POJOs using the Java Persistence API with EclipseLink. Establishing the baseline and migration to a new version of the database is handled using Flyway, and finally; reports are generated using Apache FreeMarker.

## Database configuration

The Database configuration page in preferences (**Timekeeper > Database**) allows you to configure where the database for the running Eclipse instance should be kept. The default is to place it in the shared location, under `.timekeeper` in your home folder. But you can also use a workspace relative path, or even a H2 server if you have one running.

<img src="https://github.com/turesheim/eclipse-timekeeper/raw/master/resources/screenshots/preferences-database.png" width="50%"/>

Multiple instances of the Timekeeper can share the database as it utilizes a H2 feature called mixed mode. This will automatically start a server instance on port 9090 if more connections are needed.

The Export and Import buttons are used for exactly that. CSV files, one for each table, are created once a destination folder has been selected. Note that when importing, the data is merged with what’s already in the database. So if you at some time want to start with a clean sheet, it you will have to delete the database files while no Timekeeper instance is  running.

## Installing

You can install the latest **public release** from the <a href="http://marketplace.eclipse.org/content/timekeeper-eclipse">Eclipse Marketplace</a> or drag <a href="http://marketplace.eclipse.org/marketplace-client-intro?mpc_install=2196325" title="Drag and drop into a running Eclipse workspace to install Eclipse Timekeeper"><img src="https://marketplace.eclipse.org/sites/all/themes/solstice/public/images/marketplace/btn-install.png" height="28px"/>
</a> into an running Eclipse instance. The latest CI build artifacts can be found under [GitHub Actions](https://github.com/turesheim/eclipse-timekeeper/actions?query=workflow%3ABuild). In order to install from there you must download the _p2-repository_ zip file and point your Eclipse instance to that. 

## Building

Clone the project and from the root execute:

    mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent verify

When the build completes successfully there will be a Eclipse p2 repository at *net.resheim.eclipse.timekeeper-site/target/repository* which you can install from.

## Note

This project started out as an experiment, attempting to make use of the *Java 8 Date/Time API* along with new collection features such as *Streams*. Hence **Java 8** is absolutely required for this feature to work.

This project is using [JProfiler](https://www.ej-technologies.com/products/jprofiler/overview.html) for debugging performance issues.

## License

Copyright © 2014-2020 Torkild Ulvøy Resheim. All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html


[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fturesheim%2Feclipse-timekeeper.svg?type=large)](https://app.fossa.com/projects/git%2Bgithub.com%2Fturesheim%2Feclipse-timekeeper?ref=badge_large)