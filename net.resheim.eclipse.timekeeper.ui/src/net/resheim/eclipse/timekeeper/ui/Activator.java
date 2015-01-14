/*******************************************************************************
 * Copyright (c) 2014 Torkild U. Resheim.
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle
 */
@SuppressWarnings("restriction")
public class Activator extends AbstractUIPlugin {


	public static final String ATTR_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$

	public static final String ATTR_GROUPING = ATTR_ID + ".grouping"; //$NON-NLS-1$

	public static final String KIND_BUGZILLA = "bugzilla"; //$NON-NLS-1$

	public static final String KIND_GITHUB = "github"; //$NON-NLS-1$

	public static final String KIND_LOCAL = "local"; //$NON-NLS-1$

	public static final String KIND_JIRA = "jira"; //$NON-NLS-1$

	private static final String KV_SEPARATOR = "="; //$NON-NLS-1$

	private static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$

	// The shared instance
	private static Activator plugin;

	// The plug-in ID
	public static final String PLUGIN_ID = "net.resheim.eclipse.timekeeper.ui"; //$NON-NLS-1$

	public static final String START = "start"; //$NON-NLS-1$
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
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}
	/**
	 * Returns an image descriptor for the image file at the given plug-in
	 * relative path
	 *
	 * @param path
	 *            the path
	 * @return the image descriptor
	 */
	public static ImageDescriptor getImageDescriptor(String path) {
		return imageDescriptorFromPlugin(PLUGIN_ID, path);
	}
	public static int getIntValue(ITask task, String key) {
		String value = getValue(task, key);
		if (value != null) {
			return Integer.parseInt(value);
		}
		return 0;
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
			attribute = new String();
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

	private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE d").withLocale(Locale.US);

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
