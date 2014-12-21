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
package net.resheim.eclipse.timekeeper;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.tasks.core.ITask;
import org.osgi.framework.BundleActivator;

@SuppressWarnings("restriction")
public class TimekeeperPlugin extends Plugin implements BundleActivator {

	public static final String ATTR_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	public static final String KIND_BUGZILLA = "bugzilla"; //$NON-NLS-1$
	public static final String KIND_GITHUB = "github"; //$NON-NLS-1$
	public static final String KIND_LOCAL = "local"; //$NON-NLS-1$

	private static final String KV_SEPARATOR = "="; //$NON-NLS-1$

	private static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$

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
	public static String getProjectName(AbstractTask task) {
		String c = task.getConnectorKind();
		switch (c) {
		case TimekeeperPlugin.KIND_GITHUB:
		case TimekeeperPlugin.KIND_LOCAL:
			return getParentContainerSummary(task);
		case TimekeeperPlugin.KIND_BUGZILLA:
			return task.getAttribute("product"); //$NON-NLS-1$
		default:
			return "<unknown>"; //$NON-NLS-1$
		}
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
				sb.append(kv[0]);
				sb.append('=');
				sb.append(kv[1]);
				if (i < split.length - 1) {
					sb.append(';');
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

}
