package net.resheim.eclipse.timekeeper.nativeui.test;

import static org.junit.Assert.*;
import org.eclipse.core.runtime.Platform;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * An Eclipse runtime with no Mylyn provider bundles, including the optional
 * adapter.
 */
public class StandaloneWithoutMylynTest extends NativeProjectTaskScenario {
	@Override
	protected void assertUnprojected(Project project, Task... tasks) {
		assertNull(Platform.getBundle("org.eclipse.mylyn.tasks.core"));
		assertNull(Platform.getBundle("org.eclipse.mylyn.tasks.ui"));
		assertNull(Platform.getBundle("net.resheim.eclipse.timekeeper.mylyn"));
		assertTrue(TimekeeperUiPlugin.getDefault().getTaskIntegration().isEmpty());
	}
}
