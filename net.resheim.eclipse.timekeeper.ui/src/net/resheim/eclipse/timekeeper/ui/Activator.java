/*******************************************************************************
 * Copyright (c) 2014-2017 Torkild U. Resheim.
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
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
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
import org.eclipse.ui.internal.util.BundleUtility;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.internal.idle.GenericIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.IdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.MacIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.WindowsIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.X11IdleTimeDetector;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

@SuppressWarnings("restriction")
public class Activator extends AbstractUIPlugin implements IPropertyChangeListener {

	public static final String OBJ_ACTIVITY = "OBJ_ACTIVITY";
	public static final String IMG_TOOL_CURRENT = "IMG_TOOL_CURRENT";

	/** Repository attribute ID for custom grouping field */
	public static final String ATTR_GROUPING = TimekeeperPlugin.KEY_VALUELIST_ID + ".grouping"; //$NON-NLS-1$

	/** Task repository kind identifier for Bugzilla */
	public static final String KIND_BUGZILLA = "bugzilla"; //$NON-NLS-1$

	/** Task repository kind identifier for GitHub */
	public static final String KIND_GITHUB = "github"; //$NON-NLS-1$

	/** Task repository kind identifier for JIRA */
	public static final String KIND_JIRA = "jira"; //$NON-NLS-1$

	/** Task repository kind identifier for local tasks */
	public static final String KIND_LOCAL = "local"; //$NON-NLS-1$

	/** The number of milliseconds the system has been considered idle */
	private static long lastIdleTimeMillis;

	private static Activator plugin;

	public static final String PLUGIN_ID = "net.resheim.eclipse.timekeeper.ui"; //$NON-NLS-1$

	/**
	 * Time interval for updating elapsed time on a task (1s)
	 */
	private static final int SHORT_INTERVAL = 1000;

	/**
	 * Returns the shared plug-in instance.
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

	@Override
	protected void initializeImageRegistry(ImageRegistry reg) {
		reg.put(OBJ_ACTIVITY, ImageDescriptor
				.createFromURL(BundleUtility.find(getBundle(), "icons/full/eview/activity_obj.png")));
		reg.put(IMG_TOOL_CURRENT, ImageDescriptor
				.createFromURL(BundleUtility.find(getBundle(), "icons/full/elcl/cur_nav.png")));
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
	 * @return the project name or "&lt;undetermined&gt;"
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
		} catch (CoreException e) {
			e.printStackTrace();
		}
		return "<undetermined>";
	}

	/** Platform specific idle time detector */
	private IdleTimeDetector detector;

	boolean dialogIsOpen = false;

	// TODO: use system locale?
	private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE d").withLocale(Locale.US);

	private Listener reactivationListener;

	/** The last time any activity was detected */
	protected LocalDateTime lastActiveTime;

	/** The number of milliseconds before user is considered idle */
	private static long consideredIdleThreshold;

	/**
	 * The number of milliseconds before user is considered away. The value is
	 * obtained from preferences
	 */
	private static long afkInterval;

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
	 * Must be called by the UI-thread. Handles that the session has been idle
	 * and just reactivated.
	 *
	 * @param idleTimeMillis
	 */
	private void handleReactivation(long idleTimeMillis) {
		// We want only one dialog open.
		if (dialogIsOpen) {
			return;
		}
		synchronized (this) {
			if (idleTimeMillis < lastIdleTimeMillis && lastIdleTimeMillis > consideredIdleThreshold) {
				// If we have an active task
				ITask task = TasksUi.getTaskActivityManager().getActiveTask();
				TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
				// and we have recorded a starting point
				if (task != null && ttask.getCurrentActivity().isPresent()) {
					dialogIsOpen = true;
					LocalDateTime ticked = ttask.getTick();

					// If the user have been idle, but not long enough to be
					// considered AFK we will ask whether or not to add the
					// idle time to the total.
					if (lastIdleTimeMillis < afkInterval) {
						String time = DurationFormatUtils.formatDuration(lastIdleTimeMillis, "H:mm:ss", true);
						StringBuilder sb = new StringBuilder();
						if (task.getTaskKey() != null) {
							sb.append(task.getTaskKey());
							sb.append(": ");
						}
						sb.append(task.getSummary());
						MessageDialog md = new MessageDialog(Display.getCurrent().getActiveShell(),
								"Disregard idle time?", null,
								MessageFormat.format(
										"The computer has been idle since {0}, more than {1}. Stop current activity and set end time to last active time? A new activity will be started from now.",
										ticked.format(DateTimeFormatter.ofPattern("EEE e, HH:mm:ss", Locale.US)), time),
								MessageDialog.QUESTION, new String[] { "No", "Yes" }, 1);
						int open = md.open();
						dialogIsOpen = false;
						if (open == 1) {
							// set time to the last activity detected
							ttask.endActivity(ticked);
							// and create a new activity
							ttask.startActivity();
						}
					} else {
						// If the user has been idle long enough to be
						// considered away, the idle time will be ignored
						// TODO: Control by preference
						ttask.endActivity(ticked);
						String time = DurationFormatUtils.formatDuration(lastIdleTimeMillis, "H:mm:ss", true);
						TasksUi.getTaskActivityManager().deactivateTask(ttask.getTask());
						MessageDialog.openInformation(Display.getCurrent().getActiveShell(),
								"Task automatically deactivated",
								MessageFormat.format(
										"You have been away for too long (since {0}) and the currently activity was automatically ended at {1}.",
										time,
										ticked.format(DateTimeFormatter.ofPattern("EEE e, HH:mm:ss", Locale.US))));
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
	public boolean isIdle() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null) {
			return lastIdleTimeMillis > consideredIdleThreshold;
		}
		return false;
	}

	/**
	 * Returns the current active time, or <code>null</code> if a task has not
	 * been started yet.
	 *
	 * @return the active time or <code>null</code>
	 */
	public LocalDateTime getActiveSince() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null) {
			TrackedTask trackedTask = TimekeeperPlugin.getDefault().getTask(task);
			Optional<Activity> currentActivity = trackedTask.getCurrentActivity();
			if (currentActivity.isPresent()) {
				return currentActivity.get().getStart();
			}
		}
		return null;
	}

	/**
	 * Returns the estimated time the user stopped activty, or <code>null</code>
	 * if a task has not been started.
	 *
	 * @return the estimated time when user stopped activity
	 */
	public LocalDateTime getIdleSince() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null) {
			return lastActiveTime.minus(consideredIdleThreshold, ChronoUnit.MILLIS);
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
					// get the currently active Mylyn task if any
					ITask task = TasksUi.getTaskActivityManager().getActiveTask();
					if (null != task) {
						if (idleTimeMillis < lastIdleTimeMillis && lastIdleTimeMillis > consideredIdleThreshold) {
							// has been idle for too long, but is now activated
							Display.getDefault().syncExec(() -> handleReactivation(idleTimeMillis));
						} else if (lastIdleTimeMillis < consideredIdleThreshold) {
							lastActiveTime = LocalDateTime.now();
							TimekeeperPlugin.getDefault().getTask(task).setTick(lastActiveTime);
						}
					}
					lastIdleTimeMillis = idleTimeMillis;
				}
			}
		}, SHORT_INTERVAL, SHORT_INTERVAL);

		// Immediately run the idle handler if the system has been idle and
		// the user has pressed a key or mouse button _inside_ the running
		// application.
		reactivationListener = new Listener() {
			public void handleEvent(Event event) {
				long idleTimeMillis = detector.getIdleTimeMillis();
				if (idleTimeMillis < lastIdleTimeMillis && lastIdleTimeMillis > consideredIdleThreshold) {
					handleReactivation(idleTimeMillis);
				}
				lastIdleTimeMillis = idleTimeMillis;
			}
		};
		final Display display = PlatformUI.getWorkbench().getDisplay();
		display.syncExec(new Runnable() {
			@Override
			public void run() {
				display.addFilter(SWT.KeyUp, reactivationListener);
				display.addFilter(SWT.MouseUp, reactivationListener);
			}
		});
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		consideredIdleThreshold = getPreferenceStore().getInt(PreferenceConstants.MINUTES_IDLE) * 60_000;
		afkInterval = getPreferenceStore().getInt(PreferenceConstants.MINUTES_AWAY) * 60_000;
		getPreferenceStore().addPropertyChangeListener(this);
		installTaxameter();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		getPreferenceStore().removePropertyChangeListener(this);
		super.stop(context);
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().equals(PreferenceConstants.MINUTES_IDLE)) {
			consideredIdleThreshold = Integer.parseInt(event.getNewValue().toString()) * 60_000;
		}
		if (event.getProperty().equals(PreferenceConstants.MINUTES_AWAY)) {
			afkInterval = Integer.parseInt(event.getNewValue().toString()) * 60_000;
		}
	}

	public static TrackedTask getActiveTrackedTask() {
		ITask task = TasksUi.getTaskActivityManager().getActiveTask();
		if (task != null) {
			return TimekeeperPlugin.getDefault().getTask(task);
		} else {
			return null;
		}
	}

}
