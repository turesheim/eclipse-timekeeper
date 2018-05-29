## Changes

### Version 2.0.0 (Next release)

The main goal of this release was to make sure records were not lost if your workspace got wiped. To do this a shared SQL database ([H2](http://www.h2database.com)) containing all Timekeeper data from all workspaces is used. Old data should be automatically converted to an old workspace once you upgrade.

Additionally, the data structure has been reworked so as few as possible are kept in the Mylyn Task metadata, but rather stored in the database. When creating a new Eclipse Workspace with a fresh Mylyn Tasks database, the Timekeeper connections to Mylyn will be remade. Note that the connection to any _local_ tasks will be lost unless you make a backup of the tasks. In order to separate the various local instances a unique identifier is written to the Mylyn end.

A preference setting allows you to change the database URL in case you don't want the default location. And lastly, there is a CSV export/import mechanism along with configurable templates for reporting.

Another issue with the 1.x releases was that the mechanism keeping track of passed time would sometimes create a large umber of UI events that could not catch up and cause a "spinning beachball of death" on macOS, similarly on other operating systems. This has been resolved by introducing the concept of an "activity". This basically has a start time, end time and a comment. So you can have multiple activities for each task, each with a period of time being summed up on each task. The times of the activity can be manually changed, but it is no longer possible to just specify the amount of time. The time tracker no longer needs to continously update its data.

* Added _activities_ to tasks to help track time.
* Added improved export to CSV, added import from CSV.
* Improved tracking of passed time.
* The *workweek* view will no longer lose focus while editing when tasks are refreshed.
* Added high-resolution icons for HighDPI displays.
* Added configurable templates for reporting and exporting.

#### Known issues

Timekeeper will be unable to connect to the database when restarting an Eclipse Oxygen (4.7) instance after installing a new plug-in or feature. The solution is to simply close your Eclipse instance and start it again. It appears to work just fine in Photon (4.8).