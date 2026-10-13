package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Ports.*;

/** JDBC persistence adapter. Every read/write statement is bound to an owner. */
@ApplicationScoped
public class ServerStore {
    @Inject AgroalDataSource datasource;
    @Inject TokenRegistry tokens;
    @Inject BackendClock clock;
    private final java.util.concurrent.ConcurrentMap<String,String> partitionDates=new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService maintenance;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="quarkus.datasource.jdbc.url") String jdbcUrl;
    private final ThreadLocal<Context> context = new ThreadLocal<>();
    record Context(String owner, Connection connection, Instant now) { }
    void start(@Observes StartupEvent event) throws Exception {
        DatabaseConfiguration.validate(jdbcUrl);
        try (var connection = datasource.getConnection()) {
            ServerSchema.initializeOrValidate(connection);
            // Apply only after validation: an acknowledged commit must survive process kill.
            if (!postgres()) {
                try(var statement=connection.createStatement()) {statement.execute("SET WRITE_DELAY 0");}
            }
        }
        initializeConfiguredOwners();
        maintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
            var thread=new Thread(work,"Timekeeper day boundaries");thread.setDaemon(true);return thread;
        });
        maintenance.scheduleWithFixedDelay(this::maintainDays,1,1,java.util.concurrent.TimeUnit.SECONDS);
    }
    @jakarta.annotation.PreDestroy void stopMaintenance() {if(maintenance!=null) maintenance.shutdownNow();}
    private static String partitionDate(String zone,Instant now) {
        return zone+":"+now.atZone(java.time.ZoneId.of(zone)).toLocalDate();
    }
    private void maintainDays() {
        try {
            var owners=new java.util.HashMap<String,String>();
            try(var connection=datasource.getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT id,zone_id FROM tk_owner")) {
                while(rows.next()) owners.put(rows.getString(1),rows.getString(2));
            }
            Instant now=clock.now();
            for(var entry:owners.entrySet()) if(!partitionDate(entry.getValue(),now).equals(partitionDates.get(entry.getKey())))
                transaction(entry.getKey(),()->null);
        } catch(RuntimeException | SQLException failure) {
            System.getLogger(ServerStore.class.getName()).log(System.Logger.Level.WARNING,"Could not maintain daily activity boundaries",failure);
        }
    }
    void initializeConfiguredOwners() {
        for (String owner : tokens.load().stream().map(TokenRegistry.Token::owner).distinct().toList()) {
            transaction(owner,() -> { initializeDefaultLabels(); return null; });
        }
    }
    private void initializeDefaultLabels() {
        if (!list("LABEL").isEmpty()) return;
        var service=service();
        for (var label : DefaultLabels.definitions()) {
            service.createLabel(new net.resheim.eclipse.timekeeper.service.Commands.CreateLabel(
                    label.name(),Optional.of(label.hexColor())));
        }
    }
    public <T> T transaction(String owner, Supplier<T> work) {
        Context existing = context.get();
        if (existing != null) {
            if (!existing.owner().equals(owner)) throw new IllegalStateException("Cross-owner nested transaction");
            return work.get();
        }
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Provision a configured token owner without overwriting existing preferences.
                boolean created=false;
                try (var insert = connection.prepareStatement("INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) VALUES(?,'UTC',0,0,?)"
                        + (postgres() ? " ON CONFLICT (id) DO NOTHING" : ""))) {
                    insert.setString(1,owner);
                    byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);insert.setString(2,java.util.HexFormat.of().formatHex(secret));
                    try { created=insert.executeUpdate()!=0; } catch (SQLException e) { if (!"23505".equals(e.getSQLState())) throw e; }
                }
                try (var lock = connection.prepareStatement("SELECT revision FROM tk_owner WHERE id=? FOR UPDATE")) {
                    lock.setString(1,owner); try (var rows = lock.executeQuery()) { if (!rows.next()) throw new SQLException("Missing owner"); }
                }
                context.set(new Context(owner,connection,clock.now()));
                if (created) initializeDefaultLabels();
                String date=partitionDate(profile().get("zoneId").asText(),now());
                if(!date.equals(partitionDates.get(owner))) service().rolloverActivities(new OwnerId(owner));
                T result = work.get(); connection.commit(); partitionDates.put(owner,date);return result;
            } catch (Throwable e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally { context.remove(); }
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    private RuntimeException databaseFailure(SQLException e) {
        if ("23505".equals(e.getSQLState()) || "23503".equals(e.getSQLState()) || "23506".equals(e.getSQLState())) return ApiFailure.conflict();
        return new ApiFailure(503,"UNAVAILABLE");
    }
    Context current() { Context value = context.get(); if (value == null) throw new IllegalStateException("No owner transaction"); return value; }
    public String owner() { return current().owner(); }
    public Instant now() { return current().now(); }
    PreparedStatement statement(String sql, Object... parameters) throws SQLException {
        var statement = current().connection().prepareStatement(sql);
        for (int i=0;i<parameters.length;i++) statement.setObject(i+1,parameters[i]);
        return statement;
    }
    int update(String sql, Object... parameters) {
        try (var statement = statement(sql,parameters)) { return statement.executeUpdate(); }
        catch (SQLException e) { throw databaseFailure(e); }
    }
    public long revision() {
        try (var statement = statement("SELECT revision FROM tk_owner WHERE id=?",owner()); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Missing owner"); return rows.getLong(1);
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public byte[] cursorSecret() {
        try (var statement=statement("SELECT cursor_secret FROM tk_owner WHERE id=?",owner());var rows=statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Missing owner");return java.util.HexFormat.of().parseHex(rows.getString(1));
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public ObjectNode profile() {
        try (var statement = statement("SELECT zone_id,version FROM tk_owner WHERE id=?",owner()); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Missing owner");
            var value = object(); value.put("ownerId",owner()); value.put("zoneId",rows.getString(1)); value.put("version",rows.getLong(2)); return value;
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public ObjectNode updateProfile(String zone) {
        var profile = profile(); long version = profile.get("version").asLong();
        if (update("UPDATE tk_owner SET zone_id=?,version=? WHERE id=? AND version=?",zone,version+1,owner(),version)!=1) throw new ApiFailure(412,"STALE_VERSION");
        change("PROFILE","UPDATED",owner()); return profile();
    }
    public Optional<JsonNode> find(String type, UUID id) {
        try (var statement = statement("SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",owner(),type,id); var rows = statement.executeQuery()) {
            if (!rows.next()) return Optional.empty(); return Optional.of(checkedPayload(rows.getObject(1,UUID.class),rows.getLong(2),rows.getString(3)));
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public List<JsonNode> list(String type) {
        List<JsonNode> list = new ArrayList<>();
        try (var statement = statement("SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? ORDER BY id",owner(),type); var rows = statement.executeQuery()) {
            while (rows.next()) list.add(checkedPayload(rows.getObject(1,UUID.class),rows.getLong(2),rows.getString(3)));
            return List.copyOf(list);
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    private JsonNode checkedPayload(UUID id, long version, String json) {
        JsonNode value = parse(json);
        if (!owner().equals(value.path("ownerId").asText()) || !id.toString().equals(value.path("id").asText()) || version!=value.path("version").asLong(-1))
            throw new IllegalStateException("Stored resource identity/version mismatch");
        return value;
    }
    public <T> T save(String type,T value) {
        var node = snapshot(owner(),value); UUID id = uuid(node.get("id")); long version = node.get("version").asLong();
        if (version==0) update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",owner(),type,id,version,node.toString());
        else if (update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version,node.toString(),owner(),type,id,version-1)!=1)
            throw new ApiFailure(412,"STALE_VERSION");
        update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=?",owner(),type,id);
        if (value instanceof Task task) {
            task.projectId().ifPresent(project -> relation(type,id,"project","PROJECT",project.value()));
            task.parentTaskId().ifPresent(parent -> relation(type,id,"parent","TASK",parent.value()));
            update("DELETE FROM tk_external_reference WHERE owner_id=? AND entity_type='TASK' AND task_id=?",owner(),id);
            task.externalReferences().forEach(reference -> {
                if (postgres()) update("INSERT INTO tk_external_reference(owner_id,provider_id,repository_id,external_id,entity_type,task_id,reference_key) VALUES(?,?,?,?,'TASK',?,?)",
                        owner(),reference.providerId(),reference.repositoryId(),reference.externalId(),id,
                        referenceKey(reference.providerId(),reference.repositoryId(),reference.externalId()));
                else update("INSERT INTO tk_external_reference(owner_id,provider_id,repository_id,external_id,entity_type,task_id) VALUES(?,?,?,?,'TASK',?)",
                        owner(),reference.providerId(),reference.repositoryId(),reference.externalId(),id);
            });
        }
        if (value instanceof Activity activity) {
            relation(type,id,"task","TASK",activity.taskId().value());
            activity.labelIds().forEach(label -> relation(type,id,"label","LABEL",label.value()));
            activity.workSessionId().ifPresent(session -> relation(type,id,"workSession","WORK_SESSION",session.value()));
            if (activity.end().isPresent()) update("DELETE FROM tk_active WHERE owner_id=? AND activity_id=?",owner(),id);
            else if (active().isEmpty()) update("INSERT INTO tk_active(owner_id,entity_type,activity_id) VALUES(?,'ACTIVITY',?)",owner(),id);
            else if (!active().orElseThrow().get("id").asText().equals(id.toString())) throw ApiFailure.conflict();
        }
        return value;
    }
    private void relation(String fromType,UUID fromId,String kind,String toType,UUID toId) {
        update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,?,?,?,?,?)",owner(),fromType,fromId,kind,toType,toId);
    }
    public void delete(String type,UUID id) {
        JsonNode existing = find(type,id).orElseThrow(ApiFailure::notFound);
        update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=?",owner(),type,id);
        if ("TASK".equals(type)) update("DELETE FROM tk_external_reference WHERE owner_id=? AND entity_type='TASK' AND task_id=?",owner(),id);
        if ("ACTIVITY".equals(type)) update("DELETE FROM tk_active WHERE owner_id=? AND activity_id=?",owner(),id);
        if (update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=? AND version=?",owner(),type,id,existing.get("version").asLong())!=1) throw new ApiFailure(412,"STALE_VERSION");
    }
    boolean related(String kind,String type,UUID id) {
        try (var statement = statement("SELECT 1 FROM tk_relation WHERE owner_id=? AND kind=? AND to_type=? AND to_id=? FETCH FIRST 1 ROW ONLY",owner(),kind,type,id); var rows = statement.executeQuery()) { return rows.next(); }
        catch (SQLException e) { throw databaseFailure(e); }
    }
    Optional<JsonNode> byReference(ExternalTaskReferenceKey reference) {
        String sql="SELECT task_id FROM tk_external_reference WHERE owner_id=? AND provider_id=? AND repository_id=? AND external_id=?"
                + (postgres() ? " AND reference_key=?" : "");
        Object[] parameters=postgres()
                ? new Object[]{owner(),reference.providerId(),reference.repositoryId(),reference.externalId(),referenceKey(reference.providerId(),reference.repositoryId(),reference.externalId())}
                : new Object[]{owner(),reference.providerId(),reference.repositoryId(),reference.externalId()};
        try (var statement = statement(sql,parameters); var rows = statement.executeQuery()) {
            return rows.next() ? find("TASK",rows.getObject(1,UUID.class)) : Optional.empty();
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public Optional<JsonNode> active() {
        try (var statement = statement("SELECT activity_id FROM tk_active WHERE owner_id=?",owner()); var rows = statement.executeQuery()) {
            if (!rows.next()) return Optional.empty();
            var value = find("ACTIVITY",rows.getObject(1,UUID.class)).orElseThrow(() -> new IllegalStateException("Missing active resource"));
            if (!value.get("end").isNull()) throw new IllegalStateException("Ended active resource"); return Optional.of(value);
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public void change(String type,String change,String id) {
        long sequence = revision()+1;
        if (sequence>9007199254740991L) throw new IllegalStateException("Sequence exhausted");
        update("UPDATE tk_owner SET revision=? WHERE id=?",sequence,owner());
        update("INSERT INTO tk_change(owner_id,sequence,entity_type,entity_id,change_type,occurred_at) VALUES(?,?,?,?,?,?)",owner(),sequence,type,id,change,timestamp(now()));
    }
    private boolean postgres() { return DatabaseConfiguration.postgres(jdbcUrl); }
    private String referenceKey(String provider, String repository, String external) {
        // Bound index size without truncating the full Unicode identity; lookups also compare all fields.
        return hash(MAPPER.createArrayNode().add(provider).add(repository).add(external).toString());
    }
    Object timestamp(Instant value) {
        // PostgreSQL timestamps have microsecond precision; preserve the API's nanoseconds.
        return postgres() ? value.toString() : OffsetDateTime.ofInstant(value,ZoneOffset.UTC);
    }
    Instant timestamp(java.sql.ResultSet rows, int column) throws SQLException {
        return postgres() ? Instant.parse(rows.getString(column)) : rows.getObject(column,OffsetDateTime.class).toInstant();
    }
    public WorkSessionService workSessionService() {
        String owner=owner();var ports=new ScopedPorts(owner);
        return new DefaultWorkSessionService(new WorkSessionPorts(ports.sessions(),ports.activities(),WorkSessionId::random,ServerStore.this::now,
                new TransactionRunner() {public <T> T required(Supplier<T> work) {return transaction(owner,work);}},
                event -> change(event.entityType().name(),event.changeType().name(),event.entityId())));
    }
    public TimekeeperService service() {
        String owner = owner(); ScopedPorts ports = new ScopedPorts(owner);
        var ids = new IdentifierSource() {
            public ProjectId newProjectId() { return new ProjectId(UUID.randomUUID()); }
            public TaskId newTaskId() { return new TaskId(UUID.randomUUID()); }
            public ActivityId newActivityId() { return new ActivityId(UUID.randomUUID()); }
            public LabelId newLabelId() { return new LabelId(UUID.randomUUID()); }
        };
        var time=new TimeSource() {
            public Instant now() {return ServerStore.this.now();}
            public java.time.ZoneId zone(OwnerId ignored) {return java.time.ZoneId.of(profile().get("zoneId").asText());}
        };
        return new DefaultTimekeeperService(new ServicePorts(ports.projects(),ports.tasks(),ports.activities(),ports.labels(),ids,time,
                new TransactionRunner() { public <T> T required(Supplier<T> work) { return transaction(owner,work); } },
                event -> change(event.entityType().name(),event.changeType().name(),event.entityId())));
    }
    private class ScopedPorts {
        private final String boundOwner;
        ScopedPorts(String owner) { boundOwner=owner; }
        void check() { if (!boundOwner.equals(owner())) throw new IllegalStateException("Owner port reused outside its scope"); }
        <T> Optional<T> get(String type,UUID id,Class<T> target) { check(); return find(type,id).map(node -> target.cast(decode(type,node))); }
        <T> List<T> all(String type,Class<T> target) { check(); return list(type).stream().map(node -> target.cast(decode(type,node))).toList(); }
        ProjectRepository projects() { return new ProjectRepository() {
            public Optional<Project> find(ProjectId id) { return get("PROJECT",id.value(),Project.class); }
            public List<Project> findAllProjects() { return all("PROJECT",Project.class); }
            public Project save(Project value) { check(); return ServerStore.this.save("PROJECT",value); }
            public void delete(ProjectId id) { check(); ServerStore.this.delete("PROJECT",id.value()); }
        }; }
        TaskRepository tasks() { return new TaskRepository() {
            public Optional<Task> find(TaskId id) { return get("TASK",id.value(),Task.class); }
            public Optional<Task> findByExternalReference(ExternalTaskReferenceKey reference) { check(); return byReference(reference).map(node -> (Task)decode("TASK",node)); }
            public List<Task> findAllTasks() { return all("TASK",Task.class); }
            public boolean existsByProject(ProjectId id) { check(); return related("project","PROJECT",id.value()); }
            public boolean existsByParent(TaskId id) { check(); return related("parent","TASK",id.value()); }
            public Task save(Task value) { check(); return ServerStore.this.save("TASK",value); }
            public void delete(TaskId id) { check(); ServerStore.this.delete("TASK",id.value()); }
        }; }
        LabelRepository labels() { return new LabelRepository() {
            public Optional<Label> find(LabelId id) { return get("LABEL",id.value(),Label.class); }
            public List<Label> findAllLabels() { return all("LABEL",Label.class); }
            public Label save(Label value) { check(); return ServerStore.this.save("LABEL",value); }
            public void delete(LabelId id) { check(); ServerStore.this.delete("LABEL",id.value()); }
        }; }
        WorkSessionRepository sessions() {return new WorkSessionRepository() {
            public Optional<WorkSession> find(WorkSessionId id) {return get("WORK_SESSION",id.value(),WorkSession.class);}
            public Optional<WorkSession> findOpenWorkSessionByOwner(OwnerId owner) {
                check();if(!boundOwner.equals(owner.value())) throw ApiFailure.notFound();
                return all("WORK_SESSION",WorkSession.class).stream().filter(value -> value.end().isEmpty()).findFirst();
            }
            public List<WorkSession> findWorkSessions(OwnerId owner) {
                check();if(!boundOwner.equals(owner.value())) throw ApiFailure.notFound();return all("WORK_SESSION",WorkSession.class);
            }
            public List<Activity> findSessionActivities(WorkSessionId id) {
                check();return all("ACTIVITY",Activity.class).stream().filter(value -> value.workSessionId().equals(Optional.of(id))).toList();
            }
            public WorkSession save(WorkSession value) {check();return ServerStore.this.save("WORK_SESSION",value);}
            public void delete(WorkSessionId id) {check();ServerStore.this.delete("WORK_SESSION",id.value());}
        };}
        ActivityRepository activities() { return new ActivityRepository() {
            public List<Activity> findAllActivities() {return all("ACTIVITY",Activity.class);}
            public Optional<Activity> find(ActivityId id) { return get("ACTIVITY",id.value(),Activity.class); }
            public Optional<Activity> findOpenByOwner(OwnerId id) { check(); if (!owner().equals(id.value())) throw ApiFailure.notFound(); return active().map(node -> (Activity)decode("ACTIVITY",node)); }
            public List<Activity> findOverlapping(Instant from,Instant to,Optional<OwnerId> owner,Optional<TaskId> task) {
                check(); if (owner.isPresent() && !boundOwner.equals(owner.get().value())) throw ApiFailure.notFound();
                return all("ACTIVITY",Activity.class).stream().filter(value -> task.isEmpty() || value.taskId().equals(task.get()))
                        .filter(value -> value.start().isBefore(to) && value.end().orElse(now()).isAfter(from)).toList();
            }
            public boolean existsByTask(TaskId id) { check(); return related("task","TASK",id.value()); }
            public boolean existsByLabel(LabelId id) { check(); return related("label","LABEL",id.value()); }
            public Activity save(Activity value) { check(); return ServerStore.this.save("ACTIVITY",value); }
            public void delete(ActivityId id) { check(); ServerStore.this.delete("ACTIVITY",id.value()); }
        }; }
    }
}
