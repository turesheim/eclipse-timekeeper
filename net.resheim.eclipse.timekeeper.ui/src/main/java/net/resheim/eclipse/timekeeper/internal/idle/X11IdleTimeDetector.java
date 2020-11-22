/*
 * JPPF.
 * Copyright (C) 2005-2014 JPPF Team.
 * http://www.jppf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.resheim.eclipse.timekeeper.internal.idle;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.statushandlers.StatusManager;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Drawable;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Instances of this class provide the computer idle time on a Linux system with
 * X11.
 *
 * @author Laurent Cohen
 * @author Torkild U. Resheim - minor adjustments to suit Eclipse Timekeeper
 */
public class X11IdleTimeDetector implements IdleTimeDetector {
	/**
	 * Structure providing info on the XScreensaver.
	 */
	public static class XScreenSaverInfo extends Structure {
		/**
		 * screen saver window
		 */
		public Window window;
		/**
		 * ScreenSaver{Off,On,Disabled}
		 */
		public int state;
		/**
		 * ScreenSaver{Blanked,Internal,External}
		 */
		public int kind;
		/**
		 * milliseconds
		 */
		public NativeLong til_or_since;
		/**
		 * milliseconds
		 */
		public NativeLong idle;
		/**
		 * events
		 */
		public NativeLong event_mask;

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		protected List getFieldOrder() {
			return Arrays.asList("window", "state", "kind", "til_or_since", "idle", "event_mask");
		}
	}

	/**
	 * Definition (incomplete) of the Xext library.
	 */
	public interface Xss extends Library {
		/**
		 * Instance of the Xext library bindings.
		 */
		Xss INSTANCE = Native.load("Xss", Xss.class);

		/**
		 * Allocate a XScreensaver information structure.
		 *
		 * @return a {@link XScreenSaverInfo} instance.
		 */
		XScreenSaverInfo XScreenSaverAllocInfo();

		/**
		 * Query the XScreensaver.
		 *
		 * @param display
		 *            the display.
		 * @param drawable
		 *            a {@link Drawable} structure.
		 * @param saver_info
		 *            a previously allocated {@link XScreenSaverInfo} instance.
		 * @return an int return code.
		 */
		int XScreenSaverQueryInfo(Display display, Drawable drawable, XScreenSaverInfo saver_info);
	}

	@Override
	public long getIdleTimeMillis() {
		X11.Window window = null;
		XScreenSaverInfo info = null;
		Display display = null;

		long idleMillis = 0L;
		try {
			display = X11.INSTANCE.XOpenDisplay(null);
			if (display == null) {
				display = X11.INSTANCE.XOpenDisplay(":0.0");
			}
			if (display == null) {
				throw new RuntimeException("Could not find a display, please setup your DISPLAY environment variable");
			}
			window = X11.INSTANCE.XDefaultRootWindow(display);
			info = new XScreenSaverInfo();
			Xss.INSTANCE.XScreenSaverQueryInfo(display, window, info);
			idleMillis = info.idle.longValue();
		} catch (UnsatisfiedLinkError e) {
			IStatus status = new Status(IStatus.ERROR, getClass(), e.getMessage());
			StatusManager.getManager().handle(status, StatusManager.LOG);
			return IdleTimeDetector.NOT_WORKING;
		} finally {
			info = null;
			if (display != null) {
				X11.INSTANCE.XCloseDisplay(display);
			}
			display = null;
		}
		return idleMillis;
	}
}
