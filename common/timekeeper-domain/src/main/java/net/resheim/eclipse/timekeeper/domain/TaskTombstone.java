package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Durable record of an explicitly deleted local task and its former provider identities. */
public record TaskTombstone(UUID id, UUID taskId, Instant deletedAt, long taskVersion,
		Set<ExternalTaskReferenceKey> externalReferences) {
	public TaskTombstone {
		id=Values.required(id,"tombstoneId");
		taskId=Values.required(taskId,"deletedTaskId");
		deletedAt=Values.required(deletedAt,"deletedAt");
		taskVersion=Values.version(taskVersion);
		externalReferences=Values.set(externalReferences,"externalReferences");
	}
	public TaskTombstone(UUID taskId,Instant deletedAt,long taskVersion,Set<ExternalTaskReferenceKey> references) {
		this(taskId,taskId,deletedAt,taskVersion,references);
	}
}
