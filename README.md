# Eclipse Timekeeper [![Build Status](https://travis-ci.org/turesheim/eclipse-timekeeper.svg?branch=master)](https://travis-ci.org/turesheim/eclipse-timekeeper)

This is a simple time-tracking plug-in integrating with [Eclipse Mylyn](http://eclipse.org/mylyn/) Tasks.

![image](https://raw.githubusercontent.com/turesheim/eclipse-timekeeper/gh-pages/screenshots/workweek-view.png)

Whenever a task is *activated* in Mylyn it will automatically show up in the **Workweek** view with a bold label, and the amount of time the task is active will be tracked. When the task is *deactivated* the active time is added to the total for the task on the particular day. It is also possible to manually edit the time by clicking into the cell. If a number is typed, such as *45*, it will be translated into minutes and show up as *0:45*. If a decimal number is entered, for example *1.5*, it will be translated to *1:30*. The decimal separator can be either "," or ".".

The context menu and toolbar buttons can be used to browse back and forward by one week. The current locale is used to determine week numbers. Left of the navigation buttons there is a button for copying the workweek in HTML format to the clipboard or exporting to file as CSV or HTML.

See the [Wiki](/eclipse-timekeeper/wiki)  for more information about how to use

## Installing

You can install from the <a href="http://marketplace.eclipse.org/content/timekeeper-eclipse">Eclipse Marketplace</a> or drag <a href="http://marketplace.eclipse.org/marketplace-client-intro?mpc_install=2196325" title="Drag and drop into a running Eclipse Indigo workspace to install Eclipse Timekeeper"><img src="https://marketplace.eclipse.org/sites/all/modules/custom/marketplace/images/installbutton.png" style="border: 0px; margin:0px; padding:0px; vertical-align:bottom;"/>
</a> into an running Eclipse instance.

## Building

Clone the project and from the root execute:

    mvn clean verify

When successful there will be a Eclipse p2 repository at *net.resheim.eclipse.timekeeper-site/target/repository* which you can install from.

## Note

This project started out as an experiment, attempting to make use of the *Java 8 Date/Time API* along with new collection features such as *Streams*. Hence **Java 8** is absolutely required to execute this bundle.

## License

Copyright © 2014-2015 Torkild Ulvøy Resheim. All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
