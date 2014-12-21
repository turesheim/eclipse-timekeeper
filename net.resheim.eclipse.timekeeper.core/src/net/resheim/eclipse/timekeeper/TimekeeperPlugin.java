package net.resheim.eclipse.timekeeper;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.osgi.framework.BundleActivator;

public class TimekeeperPlugin extends Plugin implements BundleActivator {

	private static final String KV_SEPARATOR = "="; //$NON-NLS-1$
	private static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$
	public static final String ATTR_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	public static final String START = "start"; //$NON-NLS-1$

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

	public static int getIntValue(ITask task, String key) {
		String value = getValue(task, key);
		if (value != null) {
			return Integer.parseInt(value);
		}
		return 0;
	}

}
