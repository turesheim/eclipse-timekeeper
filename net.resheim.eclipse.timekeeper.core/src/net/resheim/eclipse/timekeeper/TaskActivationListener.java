package net.resheim.eclipse.timekeeper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}
	
	
	@Override
	public void preTaskActivated(ITask task) {
		LocalDateTime now = LocalDateTime.now();
		TimekeeperPlugin.setValue(task, TimekeeperPlugin.START, now.toString());
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		String startString = TimekeeperPlugin.getValue(task, TimekeeperPlugin.START);
		if (startString != null) {
			LocalDateTime parse = LocalDateTime.parse(startString);
			LocalDateTime now = LocalDateTime.now();
			long seconds = parse.until(now, ChronoUnit.SECONDS);
			String time = now.toLocalDate().toString();
			String accumulatedString = TimekeeperPlugin.getValue(task, time);
			if (accumulatedString != null) {
				long accumulated = Long.parseLong(accumulatedString);
				accumulated = accumulated + seconds;
				TimekeeperPlugin.setValue(task, time, Long.toString(accumulated));
			} else {
				TimekeeperPlugin.setValue(task, time, Long.toString(seconds));
			}
		}
		TimekeeperPlugin.clearValue(task, TimekeeperPlugin.START);
	}

	@Override
	public void taskActivated(ITask task) {
		// Do nothing
	}

	@Override
	public void taskDeactivated(ITask task) {
		// Do nothing
	}
}
