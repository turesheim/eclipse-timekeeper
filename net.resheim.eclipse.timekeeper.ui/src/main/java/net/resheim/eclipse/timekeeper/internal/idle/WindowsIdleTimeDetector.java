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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.statushandlers.StatusManager;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser.LASTINPUTINFO;

/**
 * Instances of this class provide the computer idle time on a Windows system.
 *
 * @author Laurent Cohen
 */
public class WindowsIdleTimeDetector implements IdleTimeDetector {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getIdleTimeMillis() {
		try {
			LASTINPUTINFO lastInputInfo = new LASTINPUTINFO();
			if (!User32.INSTANCE.GetLastInputInfo(lastInputInfo)) {
				throw new IllegalStateException("GetLastInputInfo failed");
			}
			// Both values are unsigned 32-bit counters. Converting the subtraction
			// preserves the correct elapsed value when GetTickCount wraps.
			return Integer.toUnsignedLong(Kernel32.INSTANCE.GetTickCount() - lastInputInfo.dwTime);
		} catch (LinkageError | RuntimeException e) {
			IStatus status = new Status(IStatus.ERROR, getClass(), e.getMessage());
			StatusManager.getManager().handle(status, StatusManager.LOG);
			return IdleTimeDetector.NOT_WORKING;
		}
	}
}
