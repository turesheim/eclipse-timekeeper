/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.converters;

import java.time.Instant;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** Stores instants as canonical UTC ISO-8601 text without a JVM time-zone dependency. */
@Converter
public class InstantAttributeConverter implements AttributeConverter<Instant, String> {
	@Override
	public String convertToDatabaseColumn(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	@Override
	public Instant convertToEntityAttribute(String value) {
		return value == null ? null : Instant.parse(value);
	}
}
