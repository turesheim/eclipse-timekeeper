package net.resheim.eclipse.timekeeper.ui.tasks;

import org.eclipse.swt.graphics.Image;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;

/**
 * Optional Eclipse task-provider presentation. Native editing and tracking use
 * the client API.
 */
public interface TaskIntegration {
	/** Human-readable provider key; independent of the stable external ID. */
	default java.util.Optional<String> displayId(Task task) {
		return java.util.Optional.empty();
	}

	boolean isLinked(Task task);

	boolean isActive(Task task);

	Image image(Task task);

	void open(Task task);

	void activate(Task task);

	void deactivate(TaskId taskId);

	/** Clear the provider's current activation even if its tracked interval has ended. */
	void deactivateActiveTask();
}
