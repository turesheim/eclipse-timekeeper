/*******************************************************************************
 * Copyright © 2018 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.report;

import java.io.Serializable;

/**
 * A basic report template representation.
 * 
 * @author Torkild Ulvøy Resheim, Itema AS
 */
public class ReportTemplate implements Serializable {
	
	private static final long serialVersionUID = 3796302809926743470L;
	
	String name;
	String code;
	Type type;
	
	public enum Type {
		/** Plain text */
		TEXT,
		/** Hyper Text Markup Language */
		HTML,
		/** Rich Text Format */
		RTF
	}

	public ReportTemplate(String name, Type type, String code) {
		super();
		this.name = name;
		this.setType(type);
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Type getType() {
		return type;
	}

	public void setType(Type type) {
		this.type = type;
	}

}
