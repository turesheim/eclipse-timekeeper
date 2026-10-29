package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;
import java.util.Optional;

/** Optional provider list grouping; connectors only create sections they can observe. */
public record TaskListSection(UUID id, UUID taskListId, String remoteSectionId, String name,
		TaskListSectionKind kind, int position, boolean present, boolean writable, Optional<ExternalTaskProviderData> providerData,long version) {
	public TaskListSection {
		id=Values.required(id,"sectionId");taskListId=Values.required(taskListId,"taskListId");
		remoteSectionId=Values.required(remoteSectionId,"remoteSectionId");name=Values.required(name,"name");
		kind=Values.required(kind,"kind");
		if(position<0) throw new DomainValidationException("position","position must not be negative");
		providerData=providerData==null?Optional.empty():providerData;
		version=Values.version(version);
	}
    public TaskListSection(UUID id,UUID taskListId,String remoteSectionId,String name,TaskListSectionKind kind,int position,boolean present,boolean writable,long version) {
        this(id,taskListId,remoteSectionId,name,kind,position,present,writable,Optional.empty(),version);
    }
    public TaskListSection withProviderData(Optional<ExternalTaskProviderData> value) {
        return new TaskListSection(id,taskListId,remoteSectionId,name,kind,position,present,writable,value,version+1);
    }
	public TaskListSection update(String nextName,TaskListSectionKind nextKind,int nextPosition,boolean nextPresent,boolean nextWritable) {
		return new TaskListSection(id,taskListId,remoteSectionId,nextName,nextKind,nextPosition,nextPresent,nextWritable,providerData,version+1);
	}
}
