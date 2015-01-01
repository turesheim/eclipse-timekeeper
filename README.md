# Eclipse Timekeeper [![Build Status](https://travis-ci.org/turesheim/eclipse-timekeeper.svg?branch=master)](https://travis-ci.org/turesheim/eclipse-timekeeper)

This is a simple time-tracking plug-in integrating with [Eclipse Mylyn](http://eclipse.org/mylyn/) Tasks.

![image](https://raw.githubusercontent.com/turesheim/eclipse-timekeeper/gh-pages/screenshots/workweek-view.png)

Whenever a task is *activated* in Mylyn it will automatically show up in the **Workweek** view with a bold label, and the amount of time the task is active will be tracked. When the task is *deactivated* the active time is added to the total for the task on the particular day. It is also possible to manually edit the time by clicking into the cell. If a number is typed, such as *45*, it will be translated into minutes and show up as *0:45*. If a decimal number is entered, for example *1.5*, it will be translated to *1:30*. The decimal separator can be either "," or ".".

The context menu and toolbar buttons can be used to browse back and forward by one week. The current locale is used to determine week numbers.

There is built in support for *GitHub* and *Bugzilla* task repositories, however other repository types should also work. Tasks from *GitHub* are grouped by the name of the first query they appear in. Tasks from *Bugzilla* repositories are grouped by the *product*. When for instance using a *JIRA* repository you may want to group by a different field, this can be done by right clicking on a task and selecting a field from the **Set Grouping Field...** menu.

Note that the timekeeping data are stored in the task repository so they follow your workspace. If the workspace is lost, so is the timekeeping data.

## Status

This is very much work in progress so there may still be some major changes. Notably:

* It has not been tested on large task collections, performance may not be optimal.
* The storage format and location may be changed.

Also there are a few features missing to make it useful.

## Installing

There is currently no release from which you can install. Proceed to building yourself if so inclined.

## Building

Clone the project and from the root execute:

    mvn clean verify -f net.resheim.eclipse.timekeeper-parent
    
When successful there will be a Eclipse p2 repository at *net.resheim.eclipse.timekeeper-site/target/repository* which you can install from.

## Note

This project started out as an experiment, attempting to make use of the *Java 8 Date/Time API* along with new collection features such as *Streams*. Hence **Java 8** is absolutely required to execute this bundle.

## License

Copyright © 2014 Torkild Ulvøy Resheim. All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
