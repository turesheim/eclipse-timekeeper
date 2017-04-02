/*******************************************************************************
 * Copyright (c) 2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import java.time.LocalDate;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

/**
 * Notes are kept one per {@link TrackedTask} per day.
 * 
 * @author Torkild U. Resheim
 */
@Entity
public class Notes {
	
	/** The date the note was created */
	private LocalDate date;
	
	@Id
	@GeneratedValue
	private int id;
	
	private String rawText;

	public LocalDate getDate() {
		return date;
	}
	
	public int getId() {
		return id;
	}

	public String getRawText() {
		return rawText;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public void setId(int id) {
		this.id = id;
	}

	public void setRawText(String rawText) {
		this.rawText = rawText;
	}

}
