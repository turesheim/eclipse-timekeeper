package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

/** A provider list, separate from a Timekeeper project and from task sections. */
public record TaskList(UUID id, UUID connectionId, String remoteListId, String name, TaskListKind kind,
		boolean readable, boolean writable, boolean shared, boolean owner, boolean present, int position, long version) {
	public TaskList {
		id = Values.required(id, "taskListId");
		connectionId = Values.required(connectionId, "connectionId");
		remoteListId = Values.required(remoteListId, "remoteListId");
		name = Values.required(name, "name");
		kind = Values.required(kind, "kind");
		if (writable && !readable) throw new DomainValidationException("writable", "write access requires read access");
		if (position < 0) throw new DomainValidationException("position", "position must not be negative");
		version = Values.version(version);
	}

	public TaskList update(String newName, TaskListKind newKind, boolean newReadable, boolean newWritable,
			boolean newShared, boolean newOwner, boolean newPresent, int newPosition) {
		return new TaskList(id, connectionId, remoteListId, newName, newKind, newReadable, newWritable,
				newShared, newOwner, newPresent, newPosition, version + 1);
	}
}
