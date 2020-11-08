package net.resheim.eclipse.timekeeper.db.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

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
