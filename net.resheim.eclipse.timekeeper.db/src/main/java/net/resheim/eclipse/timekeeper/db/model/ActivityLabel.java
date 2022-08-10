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

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * A label has a color and a name and is used to categorize an {@link Activity}.
 * 
 * @author Torkild Ulvøy Resheim
 */
@Entity
@Table(name = "ACTIVITYLABEL")
@NamedQuery(name="ActivityLabel.findAll", query="SELECT a FROM ActivityLabel a")
public class ActivityLabel implements Serializable {
	
	private static final long serialVersionUID = -3021226114768805330L;

	@Id
	@GeneratedValue(generator = "uuid")
	@Column(name = "ID")
	private String id;
	
	@Column(name = "NAME")
	private String name;

	@Column(name = "COLOR")
	private String color;
	
	public ActivityLabel() {
	}

	public ActivityLabel(String name, String color) {
		this.name = name;
		this.color = color;
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

	public String getId() {
		return id;
	}

}
