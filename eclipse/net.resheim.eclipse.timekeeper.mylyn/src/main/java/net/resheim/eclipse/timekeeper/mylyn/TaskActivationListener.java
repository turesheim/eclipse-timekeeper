package net.resheim.eclipse.timekeeper.mylyn;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;

/** Mylyn event boundary; all Timekeeper mutations are client commands. */
public final class TaskActivationListener implements ITaskActivationListener {
	@Override
	public void preTaskActivated(ITask task) {
		var adapter = MylynIntegration.getDefault();
		if (adapter != null)
			try {
				adapter.activated(task);
			} catch (RuntimeException failure) {
				adapter.reportFailure(failure);
			}
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		var adapter = MylynIntegration.getDefault();
		if (adapter != null)
			try {
				adapter.deactivated(task);
			} catch (RuntimeException failure) {
				adapter.reportFailure(failure);
			}
	}

	@Override
	public void taskActivated(ITask task) {
	}

	@Override
	public void taskDeactivated(ITask task) {
	}
}
