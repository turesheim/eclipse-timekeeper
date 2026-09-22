package net.resheim.eclipse.timekeeper.db.adapter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.OwnerIdentity;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Ports.ActivityRepository;
import net.resheim.eclipse.timekeeper.service.Ports.EventSink;
import net.resheim.eclipse.timekeeper.service.Ports.IdentifierSource;
import net.resheim.eclipse.timekeeper.service.Ports.LabelRepository;
import net.resheim.eclipse.timekeeper.service.Ports.ProjectRepository;
import net.resheim.eclipse.timekeeper.service.Ports.ServicePorts;
import net.resheim.eclipse.timekeeper.service.Ports.TaskRepository;
import net.resheim.eclipse.timekeeper.service.Ports.TransactionRunner;
import net.resheim.eclipse.timekeeper.service.ServiceException;

/** JPA implementation of the pure-Java application-service ports. */
public final class JpaServicePorts implements ProjectRepository, TaskRepository, ActivityRepository,
		LabelRepository, IdentifierSource, TransactionRunner {
	private final Supplier<EntityManager> managers;
	private final Object transactionLock = new Object();

	public JpaServicePorts(Supplier<EntityManager> managers) {
		this.managers = managers;
	}

	public ServicePorts ports(EventSink events) {
		return new ServicePorts(this, this, this, this, this, Clock.systemUTC()::instant, this, events);
	}

	@Override
	public <T> T required(Supplier<T> work) {
		synchronized (transactionLock) {
			EntityManager manager = manager();
			EntityTransaction transaction = manager.getTransaction();
			boolean owner = !transaction.isActive();
			if (owner) transaction.begin();
			try {
				T result = work.get();
				if (owner) transaction.commit();
				return result;
			} catch (RuntimeException failure) {
				if (owner && transaction.isActive()) transaction.rollback();
				if (owner) manager.clear();
				throw failure;
			}
		}
	}

	@Override
	public Optional<Project> find(ProjectId id) {
		return findProject(id).map(this::project);
	}

	@Override
	public List<Project> findAllProjects() {
		return manager().createNamedQuery("Project.findAll", net.resheim.eclipse.timekeeper.db.model.Project.class)
				.getResultStream().map(this::project).toList();
	}

	@Override
	public Project save(Project snapshot) {
		EntityManager manager = manager();
		net.resheim.eclipse.timekeeper.db.model.Project entity = findProject(snapshot.id()).orElse(null);
		if (entity == null) {
			entity = new net.resheim.eclipse.timekeeper.db.model.Project(snapshot.id().value().toString(), snapshot.name());
			manager.persist(entity);
		} else {
			expectNext(snapshot.version(), projectVersion(entity), "project");
			if (!entity.getName().equals(snapshot.name())) entity = renameProject(entity, snapshot);
		}
		manager.flush();
		return project(entity);
	}

	@Override
	public void delete(ProjectId id) {
		findProject(id).ifPresent(manager()::remove);
	}

	@Override
	public Optional<Task> find(TaskId id) {
		return Optional.ofNullable(manager().find(net.resheim.eclipse.timekeeper.db.model.Task.class,
				id.value().toString())).map(this::task);
	}

	@Override
	public Optional<Task> findByExternalReference(ExternalTaskReferenceKey reference) {
		return manager().createNamedQuery("ExternalTaskReference.findTask",
				net.resheim.eclipse.timekeeper.db.model.Task.class)
				.setParameter("providerId", reference.providerId())
				.setParameter("repositoryId", reference.repositoryId())
				.setParameter("externalId", reference.externalId()).setMaxResults(1)
				.getResultStream().findFirst().map(this::task);
	}

	@Override
	public List<Task> findAllTasks() {
		return manager().createNamedQuery("Task.findAll", net.resheim.eclipse.timekeeper.db.model.Task.class)
				.getResultStream().map(this::task).toList();
	}

	@Override
	public boolean existsByProject(ProjectId projectId) {
		return findProject(projectId).map(project -> manager()
				.createQuery("SELECT COUNT(t) FROM Task t WHERE t.taskProject = :project", Long.class)
				.setParameter("project", project).getSingleResult() > 0).orElse(false);
	}

	@Override
	public boolean existsByParent(TaskId taskId) {
		return manager().createQuery("SELECT COUNT(t) FROM Task t WHERE t.parentTask.id = :id", Long.class)
				.setParameter("id", taskId.value().toString()).getSingleResult() > 0;
	}

	@Override
	public Task save(Task snapshot) {
		EntityManager manager = manager();
		String id = snapshot.id().value().toString();
		net.resheim.eclipse.timekeeper.db.model.Task entity = manager.find(
				net.resheim.eclipse.timekeeper.db.model.Task.class, id);
		if (entity == null) {
			entity = new net.resheim.eclipse.timekeeper.db.model.Task(id, snapshot.summary());
			manager.persist(entity);
		} else {
			expectNext(snapshot.version(), entity.getVersion(), "task");
		}
		entity.setTaskSummary(snapshot.summary());
		entity.setTaskUrl(snapshot.url().orElse(null));
		entity.setProject(snapshot.projectId().flatMap(this::findProject).orElse(null));
		entity.setParentTask(snapshot.parentTaskId().map(value -> manager().find(
				net.resheim.eclipse.timekeeper.db.model.Task.class, value.value().toString())).orElse(null));
		synchronizeReferences(entity, snapshot.externalReferences());
		manager.flush();
		return task(entity);
	}

	@Override
	public void delete(TaskId id) {
		net.resheim.eclipse.timekeeper.db.model.Task entity = manager().find(
				net.resheim.eclipse.timekeeper.db.model.Task.class, id.value().toString());
		if (entity == null) return;
		entity.setProject(null);
		entity.setParentTask(null);
		manager().remove(entity);
	}

	@Override
	public Optional<Activity> find(ActivityId id) {
		return Optional.ofNullable(manager().find(net.resheim.eclipse.timekeeper.db.model.Activity.class,
				id.value().toString())).map(this::activity);
	}

	@Override
	public Optional<Activity> findOpenByOwner(OwnerId ownerId) {
		return manager().createQuery("SELECT a FROM Activity a WHERE a.ownerId = :owner AND a.end IS NULL",
				net.resheim.eclipse.timekeeper.db.model.Activity.class).setParameter("owner", ownerId.value())
				.setMaxResults(1).getResultStream().findFirst().map(this::activity);
	}

	@Override
	public List<Activity> findOverlapping(Instant fromInclusive, Instant toExclusive,
			Optional<OwnerId> ownerId, Optional<TaskId> taskId) {
		return manager().createQuery("SELECT a FROM Activity a WHERE a.start < :end "
				+ "AND (a.end IS NULL OR a.end > :start)", net.resheim.eclipse.timekeeper.db.model.Activity.class)
				.setParameter("start", fromInclusive).setParameter("end", toExclusive).getResultStream()
				.filter(activity -> ownerId.isEmpty() || activity.getOwner().value().equals(ownerId.get().value()))
				.filter(activity -> taskId.isEmpty() || activity.getTrackedTask().getId()
						.equals(taskId.get().value().toString())).map(this::activity).toList();
	}

	@Override
	public boolean existsByTask(TaskId taskId) {
		return manager().createQuery("SELECT COUNT(a) FROM Activity a WHERE a.task.id = :id", Long.class)
				.setParameter("id", taskId.value().toString()).getSingleResult() > 0;
	}

	@Override
	public boolean existsByLabel(LabelId labelId) {
		return manager().createQuery("SELECT COUNT(a) FROM Activity a JOIN a.labels l WHERE l.id = :id", Long.class)
				.setParameter("id", labelId.value().toString()).getSingleResult() > 0;
	}

	@Override
	public Activity save(Activity snapshot) {
		EntityManager manager = manager();
		String id = snapshot.id().value().toString();
		net.resheim.eclipse.timekeeper.db.model.Activity entity = manager.find(
				net.resheim.eclipse.timekeeper.db.model.Activity.class, id);
		net.resheim.eclipse.timekeeper.db.model.Task tracked = manager.find(
				net.resheim.eclipse.timekeeper.db.model.Task.class, snapshot.taskId().value().toString());
		if (entity == null) {
			entity = new net.resheim.eclipse.timekeeper.db.model.Activity(id, tracked,
					new OwnerIdentity(snapshot.ownerId().value()), snapshot.start());
			tracked.addActivity(entity);
			manager.persist(entity);
		} else {
			expectNext(snapshot.version(), activityVersion(entity), "activity");
		}
		entity.setOwner(new OwnerIdentity(snapshot.ownerId().value()));
		entity.setStart(snapshot.start());
		entity.setEnd(snapshot.end().orElse(null));
		entity.setSummary(snapshot.summary());
		entity.setManual(snapshot.manual());
		entity.setLabels(snapshot.labelIds().stream().map(labelId -> manager.find(ActivityLabel.class,
				labelId.value().toString())).toList());
		if (snapshot.end().isEmpty()) tracked.setCurrentActivity(entity);
		else if (tracked.getCurrentActivity().orElse(null) == entity) tracked.setCurrentActivity(null);
		manager.flush();
		return activity(entity);
	}

	@Override
	public void delete(ActivityId id) {
		EntityManager manager = manager();
		net.resheim.eclipse.timekeeper.db.model.Activity entity = manager.find(
				net.resheim.eclipse.timekeeper.db.model.Activity.class, id.value().toString());
		if (entity != null) {
			if (entity.getTrackedTask() != null) entity.getTrackedTask().removeActivity(entity);
			manager.remove(entity);
		}
	}

	@Override
	public Optional<Label> find(LabelId id) {
		return Optional.ofNullable(manager().find(ActivityLabel.class, id.value().toString())).map(this::label);
	}

	@Override
	public List<Label> findAllLabels() {
		return manager().createNamedQuery("ActivityLabel.findAll", ActivityLabel.class)
				.getResultStream().map(this::label).toList();
	}

	@Override
	public Label save(Label snapshot) {
		EntityManager manager = manager();
		String id = snapshot.id().value().toString();
		ActivityLabel entity = manager.find(ActivityLabel.class, id);
		if (entity == null) {
			entity = new ActivityLabel(id, snapshot.name(), snapshot.color().orElse(null));
			manager.persist(entity);
		} else {
			expectNext(snapshot.version(), labelVersion(entity), "label");
			entity.setName(snapshot.name());
			entity.setColor(snapshot.color().orElse(null));
		}
		manager.flush();
		return label(entity);
	}

	@Override
	public void delete(LabelId id) {
		remove(ActivityLabel.class, id.value().toString());
	}

	@Override public ProjectId newProjectId() { return new ProjectId(UUID.randomUUID()); }
	@Override public TaskId newTaskId() { return new TaskId(UUID.randomUUID()); }
	@Override public ActivityId newActivityId() { return new ActivityId(UUID.randomUUID()); }
	@Override public LabelId newLabelId() { return new LabelId(UUID.randomUUID()); }

	private EntityManager manager() {
		EntityManager manager = managers.get();
		if (manager == null || !manager.isOpen()) {
			throw ServiceException.conflict("database", "Timekeeper database is not ready");
		}
		return manager;
	}

	private <T> void remove(Class<T> type, Object id) {
		T entity = manager().find(type, id);
		if (entity != null) manager().remove(entity);
	}

	private void synchronizeReferences(net.resheim.eclipse.timekeeper.db.model.Task entity,
			Set<ExternalTaskReference> references) {
		List<net.resheim.eclipse.timekeeper.db.model.ExternalTaskReference> existing =
				new ArrayList<>(entity.getExternalReferences());
		for (var reference : existing) {
			boolean retained = references.stream().anyMatch(candidate -> candidate.providerId().equals(reference.getProviderId())
					&& candidate.repositoryId().equals(reference.getRepositoryId())
					&& candidate.externalId().equals(reference.getExternalId()));
			if (!retained) entity.unlinkExternalTask(reference.getProviderId(), reference.getRepositoryId(),
					reference.getExternalId());
		}
		for (ExternalTaskReference reference : references) {
			entity.linkExternalTask(reference.providerId(), reference.repositoryId(), reference.externalId(),
					reference.externalUrl().orElse(null));
		}
	}

	private Optional<net.resheim.eclipse.timekeeper.db.model.Project> findProject(ProjectId id) {
		return manager().createNamedQuery("Project.findAll", net.resheim.eclipse.timekeeper.db.model.Project.class)
				.getResultStream().filter(project -> projectId(project).equals(id)).findFirst();
	}

	private ProjectId projectId(net.resheim.eclipse.timekeeper.db.model.Project entity) {
		String serviceId = entity.getServiceId();
		if (serviceId != null) return new ProjectId(UUID.fromString(serviceId));
		String legacyKey = "net.resheim.eclipse.timekeeper.project:" + entity.getName();
		return new ProjectId(UUID.nameUUIDFromBytes(legacyKey.getBytes(StandardCharsets.UTF_8)));
	}

	private net.resheim.eclipse.timekeeper.db.model.Project renameProject(
			net.resheim.eclipse.timekeeper.db.model.Project existing, Project snapshot) {
		EntityManager manager = manager();
		net.resheim.eclipse.timekeeper.db.model.Project replacement =
				new net.resheim.eclipse.timekeeper.db.model.Project(snapshot.id().value().toString(), snapshot.name());
		replacement.setProjectType(existing.getProjectType());
		replacement.setTasksUrl(existing.getTasksUrl());
		replacement.setProjectUrl(existing.getProjectUrl());
		replacement.setRepositoryUrl(existing.getRepositoryUrl());
		replacement.setExternalId(existing.getExternalId());
		manager.persist(replacement);
		for (var task : new ArrayList<>(existing.getTasks())) task.setProject(replacement);
		for (var activity : new ArrayList<>(existing.getChildren())) {
			existing.removeActivity(activity);
			replacement.addActivity(activity);
			activity.setProject(replacement);
		}
		manager.remove(existing);
		return replacement;
	}

	private long projectVersion(net.resheim.eclipse.timekeeper.db.model.Project entity) {
		return contentVersion(entity.getName());
	}

	private long labelVersion(ActivityLabel entity) {
		return contentVersion(entity.getName(), entity.getColor());
	}

	private long activityVersion(net.resheim.eclipse.timekeeper.db.model.Activity entity) {
		String labels = entity.getLabels().stream().map(ActivityLabel::getId).sorted(Comparator.naturalOrder())
				.reduce("", (left, right) -> left + "\u001f" + right);
		return contentVersion(entity.getStart(), entity.getEnd(), entity.getSummary(), entity.getOwner().value(),
				entity.isEdited(), labels);
	}

	/** Stable content token for legacy tables that predate explicit version columns. */
	private long contentVersion(Object... values) {
		long hash = 0xcbf29ce484222325L;
		for (Object value : values) {
			String text = String.valueOf(value);
			for (int index = 0; index < text.length(); index++) {
				hash ^= text.charAt(index);
				hash *= 0x100000001b3L;
			}
			hash ^= 0xff;
		}
		hash &= Long.MAX_VALUE;
		return hash == 0 ? 1 : hash;
	}

	private Project project(net.resheim.eclipse.timekeeper.db.model.Project entity) {
		return new Project(projectId(entity), entity.getName(), projectVersion(entity));
	}

	private Task task(net.resheim.eclipse.timekeeper.db.model.Task entity) {
		Optional<ProjectId> project = Optional.ofNullable(entity.getProject())
				.map(this::projectId);
		Set<ExternalTaskReference> references = entity.getExternalReferences().stream()
				.map(value -> new ExternalTaskReference(value.getProviderId(), value.getRepositoryId(),
						value.getExternalId(), value.getExternalUrl())).collect(java.util.stream.Collectors.toSet());
		Optional<TaskId> parent = Optional.ofNullable(entity.getParentTask())
				.map(value -> new TaskId(UUID.fromString(value.getId())));
		return new Task(new TaskId(UUID.fromString(entity.getId())), project, parent, entity.getTaskSummary(),
				Optional.ofNullable(entity.getTaskUrl()), references, entity.getVersion());
	}

	private Activity activity(net.resheim.eclipse.timekeeper.db.model.Activity entity) {
		Set<LabelId> labels = entity.getLabels().stream()
				.map(value -> new LabelId(UUID.fromString(value.getId())))
				.collect(java.util.stream.Collectors.toSet());
		return new Activity(new ActivityId(UUID.fromString(entity.getId())),
				new TaskId(UUID.fromString(entity.getTrackedTask().getId())), new OwnerId(entity.getOwner().value()),
				entity.getStart(), Optional.ofNullable(entity.getEnd()), entity.getSummary(), labels,
				entity.isEdited(), activityVersion(entity));
	}

	private Label label(ActivityLabel entity) {
		return new Label(new LabelId(UUID.fromString(entity.getId())), entity.getName(),
				Optional.ofNullable(entity.getColor()), labelVersion(entity));
	}

	private void expectNext(long requested, long persisted, String field) {
		if (requested != persisted + 1) {
			throw ServiceException.conflict(field, "stale " + field + " version");
		}
	}
}
