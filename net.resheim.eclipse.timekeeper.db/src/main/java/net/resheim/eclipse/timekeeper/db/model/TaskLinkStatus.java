/*******************************************************************************
 * Copyright © 2022 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.model;
/**
 * Indicates whether or not the task is linked to a Mylyn task.
 *  
 * @author Torkild U. Resheim
 */
public enum TaskLinkStatus {
	UNDETERMINED,
	LINKED,
	UNLINKED
}
