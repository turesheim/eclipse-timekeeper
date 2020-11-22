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
import com.sun.jna.Structure;

/**
 * Instances of this class provide the computer idle time on a Windows system.
 *
 * @author Laurent Cohen
 */
public class WindowsIdleTimeDetector implements IdleTimeDetector {
	/**
	 * LastInputInfo structure definition.
	 */
	public static class LastInputInfo extends Structure {
		/**
		 * The size of this structure.
		 */
		public short cbSize = 8;
		/**
		 * The tick number of the last mouse/keyboard activity.
		 */
		public int dwTime;

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		protected List getFieldOrder() {
			return Arrays.asList("cbSize", "dwTime");
		}
	}

	/**
	 * Wrapper for JNI calls to the user32 Windows library.
	 */
	public interface User32 extends Library {
		/**
		 * Instance of the User32 library bindings.
		 */
		User32 INSTANCE = Native.load("user32", User32.class);

		/**
		 * Query the time of last activity.
		 *
		 * @param info
		 *            the structure in which the last activity time is stored.
		 * @return BOOL return code.
		 */
		int GetLastInputInfo(LastInputInfo info);
	}

	/**
	 * Wrapper for JNI calls to the kernel32 Windows library.
	 */
	public interface Kernel32 extends Library {
		/**
		 * Wrapper for the native library.
		 */
		Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

		/**
		 * Retrieves the number of milliseconds that have elapsed since the
		 * system was started.
		 *
		 * @see http://msdn2.microsoft.com/en-us/library/ms724408.aspx
		 * @return number of milliseconds that have elapsed since the system was
		 *         started.
		 */
		int GetTickCount();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getIdleTimeMillis() {
		try {
			LastInputInfo lastInputInfo = new LastInputInfo();
			User32.INSTANCE.GetLastInputInfo(lastInputInfo);
			return (long) Kernel32.INSTANCE.GetTickCount() - (long) lastInputInfo.dwTime;
		} catch (Exception e) {
			IStatus status = new Status(IStatus.ERROR, getClass(), e.getMessage());
			StatusManager.getManager().handle(status, StatusManager.LOG);
			return IdleTimeDetector.NOT_WORKING;
		}
	}
}
