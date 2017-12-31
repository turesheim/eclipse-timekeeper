## Changes

### Version 2.0.0 (Next release)

The 2.0 version is a major update from the 1.0 version. The most significant change is that all date are now stored in an SQL database instead of in the Mylyn database. The SQL database is shared between _Timekeeper_ instances, so that only one instance is required. This means that all the time tracking data are stored in the same database. Additionally, when an Mylyn database is repopulated after creating a fresh workspace, the connections to the timekeeper data will be remade.

#### Known issues

Timekeeper may be unable to connect to the database when restarting after installing a new plug-in or feature. The solution is to do a normal restart. 