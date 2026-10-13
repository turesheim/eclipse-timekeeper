package net.resheim.eclipse.timekeeper.domain;

/** Immutable project aggregate state. */
public record Project(ProjectId id, String name, long version) {
	public Project {
		id = Values.required(id, "projectId");
		name = Values.required(name, "name");
		version = Values.version(version);
	}

	public Project rename(String newName) {
		return new Project(id, newName, version + 1);
	}
}
