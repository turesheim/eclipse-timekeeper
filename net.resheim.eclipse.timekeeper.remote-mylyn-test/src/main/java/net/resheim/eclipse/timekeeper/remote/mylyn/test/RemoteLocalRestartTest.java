package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import org.junit.Test;

public class RemoteLocalRestartTest {
    @Test public void returningLocalDatabaseRetainsOriginalRecordsAndProjection() throws Exception {BackendSwitchScenario.localRestart();}
}
