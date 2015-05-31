/*******************************************************************************
 * Copyright (c) 2014-2015 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import net.resheim.eclipse.timekeeper.internal.idle.GenericIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.IdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.MacIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.WindowsIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.X11IdleTimeDetector;

@SuppressWarnings("restriction")
public class Activator extends AbstractUIPlugin {


	public static final String ATTR_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$

	public static final String ATTR_GROUPING = ATTR_ID + ".grouping"; //$NON-NLS-1$

	/**
	 * The time interval of no keyboard or mouse events after which the system
	 * is considered idle (5 minutes).
	 */
	private static final int IDLE_INTERVAL = 60_000;

	/** Task repository kind identifier for Bugzilla */
	public static final String KIND_BUGZILLA = "bugzilla"; //$NON-NLS-1$

	/** Task repository kind identifier for GitHub */
	public static final String KIND_GITHUB = "github"; //$NON-NLS-1$

	/** Task repository kind identifier for JIRA */
	public static final String KIND_JIRA = "jira"; //$NON-NLS-1$

	/** Task repository kind identifier for local tasks */
	public static final String KIND_LOCAL = "local"; //$NON-NLS-1$

	private static final String KV_SEPARATOR = "="; //$NON-NLS-1$

	private static long lastIdleTime;

	private static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$

	private static Activator plugin;

	public static final String PLUGIN_ID = "net.resheim.eclipse.timekeeper.ui"; //$NON-NLS-1$

	/**
	 * Time interval for updating elapsed time on a task (1s)
	 */
	private static final int SHORT_INTERVAL = 1000;

	public static final String START = "start"; //$NON-NLS-1$

	public static final String TICK = "tick"; //$NON-NLS-1$

	private static long remainder = 0;

	/**
	 * Accumulates a number of seconds to the task on the specified date. The
	 * amount is given in milliseconds which is used to handle passed time that
	 * exceed the one second resolution that is used by the storage mechanism.
	 * This will not be exact, however the difference is negligible and will be
	 * at most one second in total.
	 *
	 * @param task
	 *            the task to add to
	 * @param dateString
	 *            the date in ISO-8601 format (uuuu-MM-dd)
	 * @param millis
	 *            the number of milliseconds to add
	 */
	public synchronized static void accumulateTime(ITask task, String dateString, long millis) {
		millis = millis + remainder;
		long seconds = millis / 1000;
		remainder = millis - (seconds * 1000);
		if (seconds == 0) {
			return;
		}
		String accumulatedString = Activator.getValue(task, dateString);
		if (accumulatedString != null) {
			long accumulated = Long.parseLong(accumulatedString);
			accumulated = accumulated + seconds;
			Activator.setValue(task, dateString, Long.toString(accumulated));
		} else {
			Activator.setValue(task, dateString, Long.toString(seconds));
		}
	}

	/**
	 * Reduces the time on a task by the given amount of seconds.
	 *
	 * @param task
	 *            the task to subtract from
	 * @param dateString
	 *            the date in ISO-8601 format (uuuu-MM-dd)
	 * @param seconds
	 *            the number of seconds to subtract
	 */
	public synchronized static void reduceTime(ITask task, String dateString, long seconds) {
		String accumulatedString = Activator.getValue(task, dateString);
		if (accumulatedString != null) {
			long accumulated = Long.parseLong(accumulatedString);
			accumulated = accumulated - seconds;
			Activator.setValue(task, dateString, Long.toString(accumulated));
		}
	}

	/**
	 * Clears the given value. This will remove both the key and the value from
	 * the task data.
	 *
	 * @param task
	 *            the task to clear
	 * @param key
	 *            the key to remove
	 */
	public static void clearValue(ITask task, String key) {
		StringBuilder sb = new StringBuilder();
		String attribute = task.getAttribute(ATTR_ID);
		if (attribute == null) {
			return;
		} else {
			String[] split = attribute.split(PAIR_SEPARATOR);
			for (int i = 0; i < split.length; i++) {
				String string = split[i];
				String[] kv = string.split(KV_SEPARATOR);
				if (kv.length == 0 || kv[0].equals(key)) {
					continue;
				}
				sb.append(kv[0]);
				sb.append('=');
				sb.append(kv[1]);
				if (i < split.length - 1) {
					sb.append(';');
				}
			}
		}
		task.setAttribute(ATTR_ID, sb.toString());
	}

	/**
	 * Returns the number of seconds the task has been active on the given date.
	 * If the task has not been active on the date, 0 will be returned.
	 *
	 * @param task
	 *            the task to get active time for
	 * @param date
	 *            the date to get active time for
	 * @return duration in seconds
	 */
	public static int getActiveTime(ITask task, LocalDate date) {
		String value = getValue(task, date.toString());
		if (value != null) {
			return Integer.parseInt(value);
		}
		return 0;
	}

	/**
	 * Returns the shared plug-in instance.
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

	/**
	 * Returns an image descriptor for the image file at the given plug-in
	 * relative path.
	 *
	 * @param path
	 *            the path relative to the plug-in root
	 * @return the image descriptor
	 */
	public static ImageDescriptor getImageDescriptor(String path) {
		return imageDescriptorFromPlugin(PLUGIN_ID, path);
	}

	/**
	 * Returns the name of the container holding the supplied task.
	 *
	 * @param task
	 *            task to find the name for
	 * @return the name of the task
	 */
	private static String getParentContainerSummary(AbstractTask task) {
		if (task.getParentContainers().size() > 0) {
			AbstractTaskContainer next = task.getParentContainers().iterator().next();
			return next.getSummary();
		}
		return null;
	}

	/**
	 * Returns the project name for the task if it can be determined.
	 *
	 * @param task
	 *            the task to get the project name for
	 * @return the project name or "&lt;unknown&gt;"
	 */
	public static String getProjectName(ITask task) {
		String c = task.getConnectorKind();
		try {
			switch (c) {
			case KIND_GITHUB:
			case KIND_LOCAL:
				return getParentContainerSummary((AbstractTask) task);
				// Bugzilla and JIRA users may want to group on different
				// values.
			case KIND_BUGZILLA:
			case KIND_JIRA:
				TaskData taskData = TasksUi.getTaskDataManager().getTaskData(task);
				if (taskData != null) {
					// This appears to be a pretty slow mechanism
					TaskRepository taskRepository = taskData.getAttributeMapper().getTaskRepository();
					String groupingAttribute = taskRepository.getProperty(ATTR_GROUPING);
					// Use custom grouping if specified
					if (groupingAttribute != null) {
						TaskAttribute attribute = taskData.getRoot().getAttribute(groupingAttribute);
						return attribute.getValue();
					} else {
						if (c.equals(KIND_BUGZILLA)) {
							return task.getAttribute("product"); //$NON-NLS-1$
						}
						return getParentContainerSummary((AbstractTask) task);
					}
				}
			default:
				break;
			}
		}catch (CoreException e){
			e.printStackTrace();
		}
		return "<undetermined>";
	}

	/**
	 * Returns the start time for the task or <code>null</code>.
	 *
	 * @param task
	 *            the task to obtain the start time for
	 * @return the start time or <code>null</code>
	 */
	public static LocalDateTime getStartTime(ITask task) {
		String startString = Activator.getValue(task, Activator.START);
		if (startString != null) {
			return LocalDateTime.parse(startString);
		}
		return null;
	}

	public static String getValue(ITask task, String key) {
		String attribute = task.getAttribute(ATTR_ID);
		if (attribute == null) {
			return null;
		} else {
			String[] split = attribute.split(PAIR_SEPARATOR);
			for (String string : split) {
				if (string.length() > 0) {
					String[] kv = string.split(KV_SEPARATOR);
					if (kv[0].equals(key)) {
						return kv[1];
					}
				}
			}
		}
		return null;
	}

	/**
	 * <pre>
	 * start = current start time
	 * year-day = duration
	 * </pre>
	 *
	 * @param task
	 * @param key
	 * @param value
	 */
	public static void setValue(ITask task, String key, String value) {
		StringBuilder sb = new StringBuilder();
		String attribute = task.getAttribute(ATTR_ID);
		if (attribute == null || attribute.length() == 0) {
			sb.append(key);
			sb.append('=');
			sb.append(value);
		} else {
			String[] split = attribute.split(PAIR_SEPARATOR);
			boolean found = false;
			for (int i = 0; i < split.length; i++) {
				String string = split[i];
				String[] kv = string.split(KV_SEPARATOR);
				if (kv[0].equals(key)) {
					kv[1] = value;
					found = true;
				}
				if (kv.length == 2) {
					sb.append(kv[0]);
					sb.append('=');
					sb.append(kv[1]);
					if (i < split.length - 1) {
						sb.append(';');
					}
				}
			}
			if (!found) {
				sb.append(';');
				sb.append(key);
				sb.append('=');
				sb.append(value);

			}
		}
		task.setAttribute(ATTR_ID, sb.toString());
	}

	/** Platform specific idle time detector */
	private IdleTimeDetector detector;

	boolean dialogIsOpen = false;
	private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE d").withLocale(Locale.US);

	private Listener reactivationListener;

	/**
	 * The constructor
	 */
	public Activator() {
	}

	/**
	 * Returns a list of weekday names and dates, using the default locale to
	 * determine the first day of the week (Sunday or Monday).
	 *
	 * @param date
	 *            starting date
	 * @return an array with weekday names and dates
	 */
	public String[] getHeadings(LocalDate date) {
		String[] headings = new String[7];
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		// Current day in the week
		int day = date.get(weekFields.dayOfWeek());
		// First date of the week
		LocalDate first = date.minusDays(day - 1);
		for (int i = 0; i < 7; i++) {
			headings[i] = formatter.format(first);
			first = first.plusDays(1);
		}
		return headings;
	}

	/**
	 * Must be called by the UI-thread
	 *
	 * @param idleTimeMillis
	 */
	private void handleReactivation(long idleTimeMillis) {
		// We want only one dialog open.
		if (dialogIsOpen) {
			return;
		}
		synchronized (this) {
			if (idleTimeMillis < lastIdleTime && lastIdleTime > IDLE_INTERVAL) {
				// If we have an active task
				ITask task = TasksUi.getTaskActivityManager().getActiveTask();
				if (task != null && Activator.getValue(task, Activator.START) != null) {
					dialogIsOpen = true;
					String tickString = Activator.getValue(task, Activator.TICK);
					LocalDateTime started = getActiveSince();
					LocalDateTime ticked = LocalDateTime.parse(tickString);
					LocalDateTime lastTick = ticked;
					// Subtract the IDLE_INTERVAL time the computer _was_
					// idle while counting up to the threshold. During this
					// period fields were updated. This must be adjusted for.
					ticked = ticked.minusNanos(IDLE_INTERVAL);
					String time = DurationFormatUtils.formatDuration(lastIdleTime, "H:mm:ss", true);

					StringBuilder sb = new StringBuilder();
					if (task.getTaskKey() != null) {
						sb.append(task.getTaskKey());
						sb.append(": ");
					}
					sb.append(task.getSummary());
					MessageDialog md = new MessageDialog(Display.getCurrent().getActiveShell(), "Disregard idle time?",
							null, MessageFormat.format(
									"The computer has been idle since {0}, more than {1}. The active task \"{2}\" was started on {3}. Subtract the idle time from the total?",
									ticked.format(DateTimeFormatter.ofPattern("EEE e, HH:mm:ss", Locale.US)),
									time, sb.toString(),
									started.format(DateTimeFormatter.ofPattern("EEE e, HH:mm:ss", Locale.US))),
							MessageDialog.QUESTION, new String[] { "No", "Yes" }, 1);
					int open = md.open();
					dialogIsOpen = false;
					if (open == 1) {
						// Subtract initial idle time
						reduceTime(task, ticked.toLocalDate().toString(), IDLE_INTERVAL / 1000);
					} else {
						// Continue task, add idle time
						LocalDateTime now = LocalDateTime.now();
						long seconds = lastTick.until(now, ChronoUnit.MILLIS);
						accumulateTime(task, ticked.toLocalDate().toString(), seconds);
						Activator.setValue(task, Activator.TICK, now.toString());
					}
				}
			}
		}
	}

	/**
	 * Returns <code>true</code> if the session is considered idle. Note that if
	 * no task is active, the session will never be considered idle.
	 *
	 * @return <code>true</code> if idle
	 */
	public boolean isIdle(){
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null && Activator.getValue(task, Activator.START) != null) {
			return lastIdleTime > IDLE_INTERVAL;
		}
		return false;
	}

	/**
	 * Returns the number of milliseconds the active task has been active
	 *
	 * @return the active milliseconds or "0"
	 */
	public long getActiveTime() {
		LocalDateTime activeSince = getActiveSince();
		if (activeSince != null) {
			LocalDateTime now = LocalDateTime.now();
			return activeSince.until(now, ChronoUnit.MILLIS);
		}
		return 0;
	}

	/**
	 * Returns the current active time, or <code>null</code> if a task has not
	 * been started.
	 *
	 * @return the active time or <code>null</code>
	 */
	public LocalDateTime getActiveSince() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null) {
			String startString = Activator.getValue(task, Activator.START);
			LocalDateTime started = LocalDateTime.parse(startString);
			return started;
		}
		return null;
	}

	/**
	 *
	 * @return
	 */
	public LocalDateTime getIdleSince() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null && Activator.getValue(task, Activator.START) != null) {
			String tickString = Activator.getValue(task, Activator.TICK);
			LocalDateTime ticked = LocalDateTime.parse(tickString);
			ticked = ticked.minusNanos(IDLE_INTERVAL);
			return ticked;
		}
		return null;
	}

	private void installTaxameter() {

		switch (Platform.getOS()) {
		case Platform.OS_MACOSX:
			detector = new MacIdleTimeDetector();
			break;
		case Platform.OS_LINUX:
			detector = new X11IdleTimeDetector();
			break;
		case Platform.OS_WIN32:
			detector = new WindowsIdleTimeDetector();
			break;
		default:
			detector = new GenericIdleTimeDetector();
			break;
		}

		Timer timer = new Timer("Timekeeper", true);
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (!PlatformUI.getWorkbench().isClosing()) {
					long idleTimeMillis = detector.getIdleTimeMillis();
					ITask task = TasksUi.getTaskActivityManager().getActiveTask();
					if (null != task) {
						if (idleTimeMillis < lastIdleTime && lastIdleTime > IDLE_INTERVAL) {
							// Was idle on last check, reactivate
							Display.getDefault().syncExec(() -> handleReactivation(idleTimeMillis));
						} else if (lastIdleTime < IDLE_INTERVAL) {
							String tickString = Activator.getValue(task, Activator.TICK);
							LocalDateTime now = LocalDateTime.now();
							LocalDateTime ticked = LocalDateTime.parse(tickString);
							// Currently not idle so accumulate spent time
							accumulateTime(task, now.toLocalDate().toString(), ticked.until(now, ChronoUnit.MILLIS));
							Activator.setValue(task, Activator.TICK, now.toString());
						}
					}
					lastIdleTime = idleTimeMillis;
				}
			}
		}, SHORT_INTERVAL, SHORT_INTERVAL);

		// Immediately run the idle handler if the system has been idle and
		// the user has pressed a key or mouse button _inside_ the running
		// application.
		reactivationListener = new Listener() {
			public void handleEvent(Event event) {
				long idleTimeMillis = detector.getIdleTimeMillis();
				if (idleTimeMillis < lastIdleTime && lastIdleTime > IDLE_INTERVAL) {
					handleReactivation(idleTimeMillis);
				}
				lastIdleTime = idleTimeMillis;
			}
		};
		final Display display = PlatformUI.getWorkbench().getDisplay();
		display.addFilter(SWT.KeyUp, reactivationListener);
		display.addFilter(SWT.MouseUp, reactivationListener);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.ui.plugin.AbstractUIPlugin#start(org.osgi.framework.BundleContext
	 * )
	 */
	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		installTaxameter();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.ui.plugin.AbstractUIPlugin#stop(org.osgi.framework.BundleContext
	 * )
	 */
	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

}
