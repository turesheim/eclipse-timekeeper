/*******************************************************************************
 * Copyright (c) 2014 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.internal.idle;

public interface IdleTimeDetector {

	public static final long NOT_WORKING = -1000;

	/**
	 * Get the System Idle Time from the OS.
	 *
	 * @return the number of milliseconds the system has been considered idle
	 */
	public long getIdleTimeMillis();

}
