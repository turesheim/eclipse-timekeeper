/*******************************************************************************
 * Copyright (c) 2026 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.eclipse.core.runtime.Platform;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.internal.idle.GenericIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.IdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.MacIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.WindowsIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.X11IdleTimeDetector;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Native smoke test for the detector selected by the running Eclipse platform. */
public class IdleTimeDetectorTest {
	@Test
	public void selectedPlatformDetectorReturnsIdleTime() throws ReflectiveOperationException {
		Field field = TimekeeperUiPlugin.class.getDeclaredField("detector");
		field.setAccessible(true);
		IdleTimeDetector detector = (IdleTimeDetector) field.get(TimekeeperUiPlugin.getDefault());
		assertNotNull(detector);
		assertEquals(expectedDetector(), detector.getClass());

		long idleTimeMillis = detector.getIdleTimeMillis();
		assertTrue("Native idle detector is unavailable on " + Platform.getOS(),
				idleTimeMillis != IdleTimeDetector.NOT_WORKING);
		assertTrue("Idle time must not be negative: " + idleTimeMillis, idleTimeMillis >= 0);
	}

	private static Class<? extends IdleTimeDetector> expectedDetector() {
		return switch (Platform.getOS()) {
			case Platform.OS_MACOSX -> MacIdleTimeDetector.class;
			case Platform.OS_LINUX -> X11IdleTimeDetector.class;
			case Platform.OS_WIN32 -> WindowsIdleTimeDetector.class;
			default -> GenericIdleTimeDetector.class;
		};
	}
}
