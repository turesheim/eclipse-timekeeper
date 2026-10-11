package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.nio.file.Files;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.ui.PlatformUI;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.nativeui.test.NativeProjectTaskScenario;

/**
 * Runs the same native CRUD scenario with the production optional adapter
 * enabled.
 */
@SuppressWarnings("restriction")
public class StandaloneProjectTaskTest extends NativeProjectTaskScenario {
	@Override
	protected void assertUnprojected(Project project, Task... tasks) throws Exception {
		PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
			var list = TasksUiPlugin.getTaskList();
			assertTrue(list.getTaskCategories().stream()
					.noneMatch(category -> category.getSummary().equals(project.name())));
			for (Task task : tasks)
				assertTrue(list.getAllTasks().stream().noneMatch(value -> task.id().value().toString().equals(value
						.getAttribute(net.resheim.eclipse.timekeeper.mylyn.MylynMetadata.ATTR_TIMEKEEPER_TASK_ID))));
		});
	}

	@Override
	protected void screenshot(SWTWorkbenchBot bot, String title, String name) throws Exception {
		Path directory = Path.of(System.getProperty("screenshots", System.getProperty("java.io.tmpdir")));
		Files.createDirectories(directory);
		PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
			org.eclipse.swt.widgets.Control control = title.isEmpty()
					? bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").widget.getParent()
					: bot.shell(title).widget;
			control.update();
			TestUtility.takeScreenshot(directory.toFile(), control, name);
		});
	}
}
