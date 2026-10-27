package net.resheim.eclipse.timekeeper.domain;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Immutable, provider-independent Timekeeper task aggregate state. */
public record Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary, Optional<String> url,
		Set<ExternalTaskReference> externalReferences, TaskPlanning planning, long version) {
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
		planning = planning == null ? TaskPlanning.unplanned() : planning;
		Set<ExternalTaskReferenceKey> keys = new HashSet<>();
		if (externalReferences.stream().map(ExternalTaskReference::key).anyMatch(key -> !keys.add(key))) {
			throw new DomainValidationException("externalReferences", "external reference keys must be unique");
		}
		version = Values.version(version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, String summary, Optional<String> url,
			Set<ExternalTaskReference> externalReferences, long version) {
		this(id, projectId, Optional.empty(), summary, url, externalReferences, TaskPlanning.unplanned(), version);
	}

	public Task(TaskId id, Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, long version) {
		this(id, projectId, parentTaskId, summary, url, externalReferences, TaskPlanning.unplanned(), version);
	}

	public Task update(Optional<ProjectId> newProjectId, Optional<TaskId> newParentTaskId,
			String newSummary, Optional<String> newUrl) {
		return new Task(id, newProjectId, newParentTaskId, newSummary, newUrl, externalReferences, planning, version + 1);
	}

	public Task plan(TaskPlanning newPlanning) {
		return new Task(id, projectId, parentTaskId, summary, url, externalReferences, newPlanning, version + 1);
	}

	public Task link(ExternalTaskReference reference) {
		Values.required(reference, "externalReference");
		Set<ExternalTaskReference> updated = new LinkedHashSet<>(externalReferences);
		updated.removeIf(candidate -> candidate.key().equals(reference.key()));
		updated.add(reference);
		return new Task(id, projectId, parentTaskId, summary, url, updated, planning, version + 1);
	}

	public Task unlink(ExternalTaskReference reference) {
		Values.required(reference, "externalReference");
		Set<ExternalTaskReference> updated = new LinkedHashSet<>(externalReferences);
		updated.removeIf(candidate -> candidate.key().equals(reference.key()));
		return new Task(id, projectId, parentTaskId, summary, url, updated, planning, version + 1);
	}
}
