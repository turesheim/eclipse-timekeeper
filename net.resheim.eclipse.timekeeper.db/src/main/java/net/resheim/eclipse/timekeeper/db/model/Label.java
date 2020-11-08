/*******************************************************************************
 * Copyright © 2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A label has a colour and a name and is used to categorize an
 * {@link Activity}.
 * 
 * @author Torkild Ulvøy Resheim, Itema AS
 */
@Entity
@Table(name = "LABEL")
public class Label {
	
	@Id
	@GeneratedValue(generator = "uuid")
	@Column(name = "ID")
	private String id;
	
	@Column(name = "NAME")
	private String name;

	@Column(name = "COLOR")
	private String color;
	
	public Label() {
		this.name="label";
		this.color="#000000";
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}


}
