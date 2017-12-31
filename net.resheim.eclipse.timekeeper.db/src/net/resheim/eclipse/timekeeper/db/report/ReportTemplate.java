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

	public ReportTemplate(String name, String code) {
		super();
		this.name = name;
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

}
