/*******************************************************************************
 * Copyright (c) 2015 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.internal.idle;

public class GenericIdleTimeDetector implements IdleTimeDetector {

	@Override
	public long getIdleTimeMillis() {
		return 0;
	}
}
