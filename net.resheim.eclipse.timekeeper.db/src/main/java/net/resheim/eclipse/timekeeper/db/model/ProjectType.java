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
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A project type is loosely connected to the Mylyn repository type. Typically "bugzilla", "
 * 
 * @author Torkild Ulvøy Resheim, Itema AS
 */
@Entity
@Table(name = "PROJECT_TYPE")
public class ProjectType implements Serializable {

	private static final long serialVersionUID = 2791471870437539526L;

	@Id
	@Column(name = "ID")
	private String id;
	
	
	public ProjectType() {
	}
	
	public ProjectType(String id) {
		super();
		this.id = id;
	}	

	public String getId() {
		return id;
	}

}
