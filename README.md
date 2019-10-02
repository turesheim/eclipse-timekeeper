# Timekeeper for Eclipse [![Build Status](https://travis-ci.org/turesheim/eclipse-timekeeper.svg?branch=master)](https://travis-ci.org/turesheim/eclipse-timekeeper) [![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=turesheim_eclipse-timekeeper&metric=alert_status)](https://sonarcloud.io/dashboard/index/turesheim_eclipse-timekeeper)

This is a simple time-tracking plug-in integrating with [Eclipse Mylyn](http://eclipse.org/mylyn/) Tasks.

![image](https://raw.githubusercontent.com/turesheim/eclipse-timekeeper/gh-pages/screenshots/workweek-view.png)

Whenever a task is *activated* in Mylyn it will automatically show up in the **Workweek** view with a bold label, and the amount of time the task is active will be tracked. An *activity* will be added to the task, which is the entity keeping track of the time and a short note. Multiple activities can be added to each task.

When the task is *deactivated* the end time is registered on the activity and the active time is added to the toal for the task on the particular day. It is also possible to manually edit the start and stop times by clicking into the cell.

The context menu and toolbar buttons can be used to browse back and forward by one week. The current locale is used to determine week numbers. Left of the navigation buttons there is a button for copying and exporting the displayed workweek in various formats. The export definitions can be modified or new ones can be added using [Freemarker](https://freemarker.apache.org) templates found in the preference settings. 

See the <a href="../../wiki">wiki</a>  for more about usage.

## Installing

You can install from the <a href="http://marketplace.eclipse.org/content/timekeeper-eclipse">Eclipse Marketplace</a> or drag <a href="http://marketplace.eclipse.org/marketplace-client-intro?mpc_install=2196325" title="Drag and drop into a running Eclipse Indigo workspace to install Eclipse Timekeeper"><img src="https://marketplace.eclipse.org/sites/all/modules/custom/marketplace/images/installbutton.png" style="border: 0px; margin:0px; padding:0px; vertical-align:bottom;"/>
</a> into an running Eclipse instance.

## Building

Clone the project and from the root execute:

    mvn clean verify

When the build completes successfully there will be a Eclipse p2 repository at *net.resheim.eclipse.timekeeper-site/target/repository* which you can install from.

## Note

This project started out as an experiment, attempting to make use of the *Java 8 Date/Time API* along with new collection features such as *Streams*. Hence **Java 8** is absolutely required for this feature to work.

This project is using [JProfiler](https://www.ej-technologies.com/products/jprofiler/overview.html) for debugging performance issues.

## License

Copyright © 2014-2019 Torkild Ulvøy Resheim. All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
