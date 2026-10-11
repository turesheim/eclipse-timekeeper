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
		String c = Optional.ofNullable(task.getConnectorKind()).orElse("mylyn");
		try {
			switch (c) {
			case KIND_GITHUB:
			case KIND_LOCAL:
				return getParentContainerSummary(task);
			// Bugzilla and JIRA users may want to group on different
			// values.
			case KIND_BUGZILLA:
			case KIND_JIRA:
				TaskData taskData = TasksUi.getTaskDataManager().getTaskData(task);
				if (taskData != null) {
					// This appears to be a pretty slow mechanism
					TaskRepository taskRepository = taskData.getAttributeMapper().getTaskRepository();
					String groupingAttribute = taskRepository.getProperty(ATTR_GROUPING);
					// Use custom grouping if specified
					if (groupingAttribute != null) {
						TaskAttribute attribute = taskData.getRoot().getAttribute(groupingAttribute);
						if (attribute != null && attribute.getValue() != null && !attribute.getValue().isBlank())
							return attribute.getValue();
					} else {
						if (c.equals(KIND_BUGZILLA)) {
							String product = task.getAttribute("product");
							if (product != null && !product.isBlank())
								return product;
							return getParentContainerSummary(task);
						}
						return getParentContainerSummary(task);
					}
				}
				break;
			default:
				break;
			}
		} catch (CoreException e) {
			log.error("Could not obtain project name", e);
		}
		return "<undetermined>";
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
