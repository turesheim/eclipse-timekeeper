package net.resheim.eclipse.timekeeper.domain;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable, provider-independent Timekeeper task aggregate state. */
public record Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary, Optional<String> url,
		Set<ExternalTaskReference> externalReferences, Set<ExternalTaskReferenceKey> externalAliases, TaskPlanning planning,
		TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, TaskRecurrence recurrence, List<TaskReminder> reminders, TaskMetadata metadata, int siblingOrder, long version) {
	public Task {
		id = Values.required(id, "taskId");
		projectId = projectId == null ? Optional.empty() : projectId;
		parentTaskId = parentTaskId == null ? Optional.empty() : parentTaskId;
		if (parentTaskId.filter(id::equals).isPresent()) {
			throw new DomainValidationException("parentTaskId", "a task cannot be its own parent");
		}
		summary = Values.required(summary, "summary");
		url = url == null ? Optional.empty() : url.map(String::trim).filter(value -> !value.isEmpty());
		externalReferences = Values.set(externalReferences, "externalReferences");
		externalAliases = Values.set(externalAliases, "externalAliases");
		planning = planning == null ? TaskPlanning.unplanned() : planning;
		checklist = checklist == null ? TaskChecklist.empty() : checklist;
		timing = timing == null ? TaskTiming.empty() : timing;
		importance = importance == null ? TaskImportance.ordinary() : importance;
		body = body == null ? TaskBody.empty() : body;
		recurrence = recurrence == null ? TaskRecurrence.empty() : recurrence;
		reminders = reminders == null ? List.of() : List.copyOf(reminders);
		metadata = metadata == null ? TaskMetadata.empty() : metadata;
		if(reminders.size()>100 || reminders.stream().map(TaskReminder::id).distinct().count()!=reminders.size())
			throw new DomainValidationException("reminders","too many or duplicate reminder IDs");
		if (siblingOrder < 0) throw new DomainValidationException("siblingOrder", "sibling order must not be negative");
		Set<ExternalTaskReferenceKey> keys = new HashSet<>();
		if (externalReferences.stream().map(ExternalTaskReference::key).anyMatch(key -> !keys.add(key))) {
			throw new DomainValidationException("externalReferences", "external reference keys must be unique");
		}
		if (externalAliases.stream().anyMatch(key -> !keys.add(key)))
			throw new DomainValidationException("externalAliases", "aliases must be unique and separate from current references");
		version = Values.version(version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, Set<ExternalTaskReferenceKey> externalAliases,
			TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body,
			TaskRecurrence recurrence, List<TaskReminder> reminders, int siblingOrder, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, TaskMetadata.empty(), siblingOrder, version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, Set<ExternalTaskReferenceKey> externalAliases,
			TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body,
			TaskRecurrence recurrence, int siblingOrder, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, List.of(), siblingOrder, version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, Set<ExternalTaskReferenceKey> externalAliases,
			TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body,
			int siblingOrder, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, TaskRecurrence.empty(), siblingOrder, version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
			TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, int siblingOrder, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, Set.of(), planning, checklist, timing, importance, body, siblingOrder, version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
			TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, body, 0, version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, String summary, Optional<String> url,
			Set<ExternalTaskReference> externalReferences, long version) {
		this(id, projectId, Optional.empty(), summary, url, externalReferences, TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, planning, TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, planning, checklist, TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, TaskImportance.ordinary(), TaskBody.empty(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, TaskBody.empty(), version);
	}

	public Task update(Optional<ProjectId> newProjectId, Optional<TaskId> newParentTaskId,
			String newSummary, Optional<String> newUrl) {
		return new Task(id, newProjectId, newParentTaskId, newSummary, newUrl, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task plan(TaskPlanning newPlanning) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, newPlanning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withChecklist(TaskChecklist newChecklist) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, newChecklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withTiming(TaskTiming newTiming) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, newTiming, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withImportance(TaskImportance newImportance) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, newImportance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withBody(TaskBody newBody) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, newBody, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withRecurrence(TaskRecurrence value) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, value, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withReminders(List<TaskReminder> value) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, value, metadata, siblingOrder, version + 1);
	}

	public Task withMetadata(TaskMetadata value) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, value, siblingOrder, version + 1);
	}

	public Task withSiblingOrder(int order) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, order, version + 1);
	}

	public Task link(ExternalTaskReference reference) {
		Values.required(reference, "externalReference");
		ExternalTaskReferenceKey key = reference.key();
		Set<ExternalTaskReference> updated = new LinkedHashSet<>(externalReferences);
		ExternalTaskReference previous = updated.stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
		if (previous != null && reference.source().equals(ExternalTaskSource.empty())) reference = reference.withSource(previous.source());
		if (previous != null && reference.taskListId().isEmpty()) reference = reference.withTaskList(previous.taskListId());
		if (previous != null && reference.sectionId().isEmpty() && reference.taskListId().equals(previous.taskListId()))
			reference = reference.withLocation(reference.taskListId(),previous.sectionId());
		if (previous != null && reference.providerData().isEmpty()) reference = reference.withProviderData(previous.providerData());
		if (previous != null && reference.baseline().isEmpty()) reference = reference.withBaseline(previous.baseline());
		updated.removeIf(candidate -> candidate.key().equals(key));
		updated.add(reference);
		Set<ExternalTaskReferenceKey> aliases = new LinkedHashSet<>(externalAliases);aliases.remove(key);
		return new Task(id, projectId, parentTaskId, summary, url, updated, aliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task replaceExternalReference(ExternalTaskReferenceKey previous, ExternalTaskReference current) {
		Values.required(previous, "previousReference");Values.required(current, "currentReference");
		ExternalTaskReferenceKey currentKey=current.key();
		if (!previous.providerId().equals(current.providerId()))
			throw new DomainValidationException("providerId", "replacement must stay within the same provider");
		if (previous.equals(currentKey)) return link(current);
		if (externalReferences.stream().noneMatch(reference -> reference.key().equals(previous)))
			throw new DomainValidationException("previousReference", "reference is not linked to task");
		if (externalReferences.stream().anyMatch(reference -> reference.key().equals(currentKey)))
			throw new DomainValidationException("currentReference", "replacement identity is already linked to task");
		ExternalTaskReference old = externalReferences.stream().filter(reference -> reference.key().equals(previous)).findFirst().orElseThrow();
		if (current.source().equals(ExternalTaskSource.empty())) current = current.withSource(old.source());
		if (current.taskListId().isEmpty()) current = current.withTaskList(old.taskListId());
		if (current.sectionId().isEmpty() && current.taskListId().equals(old.taskListId()))
			current = current.withLocation(current.taskListId(),old.sectionId());
		if (current.providerData().isEmpty()) current = current.withProviderData(old.providerData());
		if (current.baseline().isEmpty()) current = current.withBaseline(old.baseline());
		Set<ExternalTaskReference> updated = new LinkedHashSet<>(externalReferences);
		updated.removeIf(reference -> reference.key().equals(previous));updated.add(current);
		Set<ExternalTaskReferenceKey> aliases = new LinkedHashSet<>(externalAliases);
		aliases.add(previous);aliases.remove(currentKey);
		return new Task(id, projectId, parentTaskId, summary, url, updated, aliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withExternalLocation(ExternalTaskReferenceKey key,Optional<UUID> listId,Optional<UUID> sectionId) {
		Set<ExternalTaskReference> updated = new LinkedHashSet<>();
		boolean found = false;
		for (ExternalTaskReference reference : externalReferences) {
			if (reference.key().equals(key)) {
				updated.add(reference.withLocation(listId,sectionId));
				found = true;
			} else updated.add(reference);
		}
		if (!found) throw new DomainValidationException("externalReference", "reference is not linked to task");
		return new Task(id,projectId,parentTaskId,summary,url,updated,externalAliases,planning,checklist,timing,importance,body,recurrence,reminders,metadata,siblingOrder,version+1);
	}

	public Task withExternalSource(ExternalTaskReferenceKey key, ExternalTaskSource source) {
		Values.required(key, "externalReference");
		Values.required(source, "externalSource");
		Set<ExternalTaskReference> updated = new LinkedHashSet<>();
		boolean found = false;
		for (ExternalTaskReference reference : externalReferences) {
			if (reference.key().equals(key)) { updated.add(reference.withSource(source)); found = true; }
			else updated.add(reference);
		}
		if (!found) throw new DomainValidationException("externalReference", "reference is not linked to task");
		return new Task(id, projectId, parentTaskId, summary, url, updated, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task withExternalSyncState(ExternalTaskReferenceKey key, ExternalTaskSource source,
			Optional<ExternalTaskBaseline> baseline) {
		Values.required(key,"externalReference");Values.required(source,"externalSource");
		Set<ExternalTaskReference> updated=new LinkedHashSet<>();boolean found=false;
		for(ExternalTaskReference reference:externalReferences) {
			if(reference.key().equals(key)) {updated.add(reference.withSource(source).withBaseline(baseline));found=true;}
			else updated.add(reference);
		}
		if(!found) throw new DomainValidationException("externalReference","reference is not linked to task");
		return new Task(id,projectId,parentTaskId,summary,url,updated,externalAliases,planning,checklist,timing,importance,body,recurrence,reminders,metadata,siblingOrder,version+1);
	}

	public Task withExternalProviderData(ExternalTaskReferenceKey key, Optional<ExternalTaskProviderData> data) {
		Values.required(key, "externalReference");
		Set<ExternalTaskReference> updated = new LinkedHashSet<>();
		boolean found = false;
		for (ExternalTaskReference reference : externalReferences) {
			if (reference.key().equals(key)) { updated.add(reference.withProviderData(data)); found = true; }
			else updated.add(reference);
		}
		if (!found) throw new DomainValidationException("externalReference", "reference is not linked to task");
		return new Task(id, projectId, parentTaskId, summary, url, updated, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}

	public Task unlink(ExternalTaskReference reference) {
		Values.required(reference, "externalReference");
		Set<ExternalTaskReference> updated = new LinkedHashSet<>(externalReferences);
		updated.removeIf(candidate -> candidate.key().equals(reference.key()));
		return new Task(id, projectId, parentTaskId, summary, url, updated, externalAliases, planning, checklist, timing, importance, body, recurrence, reminders, metadata, siblingOrder, version + 1);
	}
}
