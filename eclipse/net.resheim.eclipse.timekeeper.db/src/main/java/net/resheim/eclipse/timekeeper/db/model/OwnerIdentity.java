/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.model;

import java.io.Serializable;
import java.util.Objects;

/** Stable identity of the person or service that owns a recorded activity. */
public record OwnerIdentity(String value) implements Serializable {
	private static final long serialVersionUID = 1L;

	/** Owner used by the single-user embedded Eclipse deployment. */
	public static final OwnerIdentity LOCAL = new OwnerIdentity("local");

	public OwnerIdentity {
		value = Objects.requireNonNull(value, "value").trim();
		if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
	}
}
