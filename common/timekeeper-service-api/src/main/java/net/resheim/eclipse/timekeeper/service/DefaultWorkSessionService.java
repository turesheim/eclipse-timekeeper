package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Ports.WorkSessionPorts;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType;

/** Session operations share the backend's owner lock and transaction boundary. */
public final class DefaultWorkSessionService implements WorkSessionService {
    private final WorkSessionPorts ports;
    public DefaultWorkSessionService(WorkSessionPorts ports) { this.ports = Objects.requireNonNull(ports); }
    private <T> T tx(Supplier<T> work) { return ports.transactions().required(work); }
    private WorkSession required(WorkSessionId id) {
        return ports.sessions().find(Objects.requireNonNull(id,"workSessionId"))
                .orElseThrow(() -> ServiceException.notFound("workSession",id));
    }
    private void version(WorkSession session, long expected) {
        if (session.version() != expected) throw ServiceException.conflict("workSession","stale work session version");
    }
    private void ensureNoOtherActive(OwnerId owner, WorkSessionId except) {
        if (ports.sessions().findOpenWorkSessionByOwner(owner).filter(value -> !value.id().equals(except)).isPresent())
            throw ServiceException.conflict("workSession","A work session is already running for this owner.");
    }
    private void event(EntityType type, ChangeType change, UUID id) {
        ports.events().publish(new TimekeeperEvent(type,change,id.toString(),ports.timeSource().now()));
    }
    @Override public WorkSession startWorkSession(StartWorkSession command) {
        return tx(() -> {
            Objects.requireNonNull(command,"command");
            return create(new CreateWorkSession(command.ownerId(),ports.timeSource().now(),Optional.empty(),command.summary()));
        });
    }
    private WorkSession create(CreateWorkSession command) {
        WorkSession.validateStart(command.start(),ports.timeSource().now());
        var session = new WorkSession(ports.identifiers().get(),command.ownerId(),command.start(),command.end(),command.summary(),0);
        if (session.end().isEmpty()) ensureNoOtherActive(session.ownerId(),null);
        session = ports.sessions().save(session); event(EntityType.WORK_SESSION,ChangeType.CREATED,session.id().value()); return session;
    }
    @Override public WorkSession createWorkSession(CreateWorkSession command) {
        return tx(() -> create(Objects.requireNonNull(command,"command")));
    }
    @Override public WorkSession stopWorkSession(StopWorkSession command) {
        return tx(() -> {
            Objects.requireNonNull(command,"command");var current = required(command.id()); version(current,command.expectedVersion());
            if (current.end().isPresent()) return current;
            var saved = ports.sessions().save(current.stop(ports.timeSource().now()));
            event(EntityType.WORK_SESSION,ChangeType.UPDATED,saved.id().value()); return saved;
        });
    }
    @Override public WorkSession updateWorkSession(UpdateWorkSession command) {
        return tx(() -> {
            Objects.requireNonNull(command,"command");var current = required(command.id()); version(current,command.expectedVersion());
            WorkSession.validateStart(command.start(),ports.timeSource().now());
            var changed = current.edit(command.start(),command.end(),command.summary());
            if (changed.end().isEmpty()) ensureNoOtherActive(current.ownerId(),current.id());
            var saved = ports.sessions().save(changed); event(EntityType.WORK_SESSION,ChangeType.UPDATED,saved.id().value()); return saved;
        });
    }
    @Override public void deleteWorkSession(DeleteWorkSession command) {
        tx(() -> {
            Objects.requireNonNull(command,"command");var current = required(command.id()); version(current,command.expectedVersion());
            if (!ports.sessions().findSessionActivities(current.id()).isEmpty())
                throw ServiceException.conflict("workSession","Remove the activity associations before deleting the work session.");
            ports.sessions().delete(current.id()); event(EntityType.WORK_SESSION,ChangeType.DELETED,current.id().value()); return null;
        });
    }
    @Override public Optional<WorkSession> workSession(WorkSessionId id) { return tx(() -> ports.sessions().find(Objects.requireNonNull(id))); }
    @Override public Optional<WorkSession> activeWorkSession(OwnerId owner) { return tx(() -> ports.sessions().findOpenWorkSessionByOwner(Objects.requireNonNull(owner))); }
    @Override public List<WorkSession> workSessions(OwnerId owner) {
        return tx(() -> ports.sessions().findWorkSessions(Objects.requireNonNull(owner)).stream()
                .sorted(Comparator.comparing(WorkSession::start).thenComparing(value -> value.id().value())).toList());
    }
    @Override public Association associateWorkSession(AssociateWorkSession command) {
        return tx(() -> {
            Objects.requireNonNull(command,"command");
            var activity = ports.activities().find(Objects.requireNonNull(command.activityId())).orElseThrow(
                    () -> ServiceException.notFound("activity",command.activityId()));
            if (activity.version() != command.expectedActivityVersion()) throw ServiceException.conflict("activity","stale activity version");
            boolean outside = false;
            if (command.workSessionId().isPresent()) {
                var session = required(command.workSessionId().get());
                if (!session.ownerId().equals(activity.ownerId())) throw ServiceException.notFound("workSession",session.id());
                outside = WorkSessionTime.calculate(session,List.of(activity),ports.timeSource().now()).outsideSession().contains(activity.id());
            }
            var saved = ports.activities().save(activity.associate(command.workSessionId().orElse(null)));
            event(EntityType.ACTIVITY,ChangeType.UPDATED,saved.id().value()); return new Association(saved,outside);
        });
    }
    @Override public Report workSessionReport(WorkSessionId id) {
        return tx(() -> {
            var session = required(id);var activities = ports.sessions().findSessionActivities(id);
            Instant now = ports.timeSource().now();
            return new Report(session,activities,WorkSessionTime.calculate(session,activities,now));
        });
    }
}
