package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

/** Optional provider list grouping; connectors only create sections they can observe. */
public record TaskListSection(UUID id, UUID taskListId, String remoteSectionId, String name,
		TaskListSectionKind kind, int position, boolean present, boolean writable, long version) {
	public TaskListSection {
		id=Values.required(id,"sectionId");taskListId=Values.required(taskListId,"taskListId");
		remoteSectionId=Values.required(remoteSectionId,"remoteSectionId");name=Values.required(name,"name");
		kind=Values.required(kind,"kind");
		if(position<0) throw new DomainValidationException("position","position must not be negative");
		version=Values.version(version);
	}
	public TaskListSection update(String nextName,TaskListSectionKind nextKind,int nextPosition,boolean nextPresent,boolean nextWritable) {
		return new TaskListSection(id,taskListId,remoteSectionId,nextName,nextKind,nextPosition,nextPresent,nextWritable,version+1);
	}
}
