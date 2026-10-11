package net.resheim.eclipse.timekeeper.mylyn;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.tasks.core.IRepositoryManager;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider metadata and stable workspace repository identity; never native
 * identity.
 */
@SuppressWarnings("restriction")
public final class MylynMetadata {
	private static final Logger log = LoggerFactory.getLogger(MylynMetadata.class);
	public static final String KIND_GITHUB = "github", KIND_LOCAL = "local", KIND_BUGZILLA = "bugzilla",
			KIND_JIRA = "jira";
	public static final String ATTR_GROUPING = "net.resheim.eclipse.timekeeper.grouping";
	public static final String GROUP_MYLYN_CATEGORY = "@mylyn-category";
	public static final String JIRA_CUSTOM_FIELD_PREFIX = "attribute.jira.custom::";
	public static final String ATTR_TIMEKEEPER_TASK_ID = "net.resheim.eclipse.timekeeper.task-id";
	private static final String LOCAL_REPO_ID = "local", LOCAL_REPO_PREFIX = "local-";
	private static final String LOCAL_REPO_KEY_ID = "net.resheim.eclipse.timekeeper.repo-id";

	private MylynMetadata() {
	}

	public static String getParentContainerSummary(ITask task) {
		Set<ITask> visited = new HashSet<>();
		ITask current = task;
		while (current instanceof AbstractTask concrete && visited.add(current)) {
			Optional<AbstractTaskContainer> category = concrete.getParentContainers().stream()
					.filter(AbstractTaskCategory.class::isInstance).findFirst();
			if (category.isPresent())
				return category.orElseThrow().getSummary();
			current = concrete.getParentContainers().stream().filter(AbstractTask.class::isInstance)
					.map(AbstractTask.class::cast).findFirst().orElse(null);
		}
		return "Uncategorized";
	}

	public static String getMylynProjectName(ITask task) {
		String kind = task.getConnectorKind();
		if (!KIND_JIRA.equals(kind) && !KIND_BUGZILLA.equals(kind))
			return getMylynProjectName(task, null, null);
		TaskRepository repository = TasksUi.getRepositoryManager().getRepository(kind, task.getRepositoryUrl());
		TaskData data = null;
		try {
			data = TasksUi.getTaskDataManager().getTaskData(task);
		} catch (CoreException failure) {
			log.warn("Could not read cached task data for grouping", failure);
		}
		if (repository == null && data != null)
			repository = data.getAttributeMapper().getTaskRepository();
		return getMylynProjectName(task, repository, data);
	}

	/** Resolve already loaded provider metadata without a network request. */
	public static String getMylynProjectName(ITask task, TaskRepository repository, TaskData data) {
		String kind = Optional.ofNullable(task.getConnectorKind()).orElse("mylyn");
		if (KIND_LOCAL.equals(kind) || KIND_GITHUB.equals(kind))
			return getParentContainerSummary(task);
		String grouping = repository == null ? null : repository.getProperty(ATTR_GROUPING);
		if (grouping != null && grouping.isBlank())
			grouping = null;
		if (KIND_JIRA.equals(kind)) {
			if (GROUP_MYLYN_CATEGORY.equals(grouping))
				return getParentContainerSummary(task);
			String attributeId = grouping == null ? TaskAttribute.PRODUCT : grouping.strip();
			if (attributeId.startsWith("customfield_"))
				attributeId = JIRA_CUSTOM_FIELD_PREFIX + attributeId;
			return groupingValue(data, attributeId).orElse("None");
		}
		if (KIND_BUGZILLA.equals(kind) && data != null) {
			if (grouping != null)
				return groupingValue(data, grouping.strip()).orElse("<undetermined>");
			String product = task.getAttribute("product");
			return product != null && !product.isBlank() ? product : getParentContainerSummary(task);
		}
		return "<undetermined>";
	}

	private static Optional<String> groupingValue(TaskData data, String id) {
		if (data == null)
			return Optional.empty();
		TaskAttribute attribute = data.getRoot().getAttribute(id);
		if (attribute == null || attribute.getValues().stream().noneMatch(value -> value != null && !value.isBlank()))
			return Optional.empty();
		String value = data.getAttributeMapper().getValueLabels(attribute).stream()
				.filter(label -> label != null && !label.isBlank()).map(String::strip).distinct()
				.collect(java.util.stream.Collectors.joining(", "));
		return value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	public static String getRepositoryUrl(ITask task) {
		String url = task.getRepositoryUrl();
		if (MylynMetadata.LOCAL_REPO_ID.equals(task.getRepositoryUrl())) {
			IRepositoryManager repositoryManager = TasksUi.getRepositoryManager();
			if (repositoryManager == null) { // may happen during testing
				return MylynMetadata.LOCAL_REPO_ID;
			}
			TaskRepository repository = repositoryManager.getRepository(task.getConnectorKind(),
					task.getRepositoryUrl());
			String id = repository.getProperty(MylynMetadata.LOCAL_REPO_KEY_ID);
			if (id == null) {
				id = LOCAL_REPO_PREFIX + UUID.randomUUID().toString();
				repository.setProperty(MylynMetadata.LOCAL_REPO_KEY_ID, id);
			}
			url = id;
		}
		return url;
	}
}
