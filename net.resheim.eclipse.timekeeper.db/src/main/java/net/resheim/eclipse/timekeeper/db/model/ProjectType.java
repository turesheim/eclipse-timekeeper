package net.resheim.eclipse.timekeeper.db.model;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

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
