package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    @Inject AccountStore accounts;
    @Inject RecoveryMail recoveryMail;
    @Inject PasskeyRealm passkeyRealm;
    @Inject ServiceSettings serviceSettings;
    private final java.util.concurrent.ConcurrentMap<String,String> partitionDates=new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService maintenance;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="quarkus.datasource.jdbc.url") String jdbcUrl;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="timekeeper.diagnostics.slow-transaction-ms",defaultValue="1000") long slowTransactionMillis;
    private static final org.jboss.logging.Logger TIMINGS=org.jboss.logging.Logger.getLogger(ServerStore.class);
    private final ThreadLocal<Context> context = new ThreadLocal<>();
    private final ThreadLocal<UUID> creationClient = new ThreadLocal<>();
    record Context(String owner, Connection connection, Instant now) { }
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="timekeeper.schema.allow-upgrade",defaultValue="false") boolean allowSchemaUpgrade;
    void start(@Observes StartupEvent event) throws Exception {
        DatabaseConfiguration.validate(jdbcUrl);
        try (var connection = datasource.getConnection()) {
            ServerSchema.initializeOrValidate(connection,allowSchemaUpgrade);
            // Apply only after validation: an acknowledged commit must survive process kill.
            if (!postgres()) {
                try(var statement=connection.createStatement()) {statement.execute("SET WRITE_DELAY 0");}
            }
        }
        accounts.initialize();
        serviceSettings.initialize();
        passkeyRealm.initialize();
        recoveryMail.initialize();
        initializeConfiguredOwners();
        maintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
            var thread=new Thread(work,"Timekeeper day boundaries");thread.setDaemon(true);return thread;
        });
        maintenance.scheduleWithFixedDelay(this::maintainDays,1,1,java.util.concurrent.TimeUnit.SECONDS);
    }
    @jakarta.annotation.PreDestroy void stopMaintenance() {
        if(maintenance==null) return;
        maintenance.shutdownNow();
        try {if(!maintenance.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
            System.getLogger(ServerStore.class.getName()).log(System.Logger.Level.WARNING,"Day-boundary maintenance did not stop within the shutdown deadline");}
        catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
    }
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
        var owners=new java.util.HashSet<String>();
        tokens.load().forEach(token -> owners.add(token.owner()));
        try(var connection=datasource.getConnection();var query=connection.createStatement();var rows=query.executeQuery("SELECT id FROM tk_owner")) {
            while(rows.next()) owners.add(rows.getString(1));
        } catch(SQLException failure) {throw databaseFailure(failure);}
        for (String owner : owners) {
            transaction(owner,() -> { initializeDefaultLabels(); migrateClientDetails(); migrateWorkSessionDescriptions(); retireSessionLinks(); return null; });
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
        long started=System.nanoTime(),acquired=0,locked=0,worked=0;String phase="pool",outcome="failed";
        try (var connection = datasource.getConnection()) {
            acquired=System.nanoTime();phase="owner-lock";
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
                locked=System.nanoTime();phase="work";
                context.set(new Context(owner,connection,clock.now()));
                AccountStore.ensureAccount(connection,owner);
                try(var initialized=statement("SELECT initialized FROM tk_account WHERE owner_id=?",owner);var rows=initialized.executeQuery()) {
                    if(!rows.next()) throw new SQLException("Missing account identity");
                    if(!rows.getBoolean(1)) {initializeDefaultLabels();update("UPDATE tk_account SET initialized=TRUE WHERE owner_id=?",owner);}
                }
                String date=partitionDate(profile().get("zoneId").asText(),now());
                if(!date.equals(partitionDates.get(owner))) service().rolloverActivities(new OwnerId(owner));
                T result = work.get();worked=System.nanoTime();phase="commit";
                connection.commit();outcome="committed";partitionDates.put(owner,date);return result;
            } catch (Throwable e) {
                if(e instanceof ApiFailure failure && failure.status>=500)outcome="server-error";
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally { context.remove(); }
        } catch (SQLException e) {outcome="database-error";throw databaseFailure(e);}
        finally {
            long ended=System.nanoTime(),elapsed=(ended-started)/1_000_000;
            if(elapsed>=Math.max(0,slowTransactionMillis) || outcome.endsWith("error")) {
                long poolEnd=acquired==0?ended:acquired,lockEnd=locked==0?ended:locked,workEnd=worked==0?ended:worked;
                TIMINGS.warnf("Database transaction outcome=%s phase=%s elapsed_ms=%d pool_ms=%d owner_lock_ms=%d work_ms=%d commit_ms=%d",
                        outcome,phase,elapsed,(poolEnd-started)/1_000_000,acquired==0?0:(lockEnd-acquired)/1_000_000,
                        locked==0?0:(workEnd-locked)/1_000_000,worked==0?0:(ended-worked)/1_000_000);
            }
        }
    }
    public <T> T authenticated(RequestIdentity identity,Supplier<T> work) {
        return transaction(identity.owner(),()->{identity.guard(current().connection());return work.get();});
    }
    private RuntimeException databaseFailure(SQLException e) {
        if ("23505".equals(e.getSQLState()) || "23503".equals(e.getSQLState()) || "23506".equals(e.getSQLState())) return ApiFailure.conflict();
        String state=e.getSQLState();
        TIMINGS.warnf("Database operation failed sql_state=%s",state!=null&&state.matches("[A-Z0-9]{5}")?state:"unknown");
        return new ApiFailure(503,"UNAVAILABLE");
    }
    Context current() { Context value = context.get(); if (value == null) throw new IllegalStateException("No owner transaction"); return value; }
    public String owner() { return current().owner(); }
    <T> T withCreationClient(UUID client,Supplier<T> work) {
        find("CLIENT",client).orElseThrow(ApiFailure::notFound);
        UUID previous=creationClient.get();creationClient.set(client);
        try {return work.get();} finally {if(previous==null) creationClient.remove();else creationClient.set(previous);}
    }
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
    private static final UUID PROFILE_PREFERENCES=UUID.fromString("00000000-0000-0000-0000-000000000001");
    public ObjectNode profile() {
        try (var statement = statement("SELECT o.zone_id,o.version,l.locale FROM tk_owner o LEFT JOIN tk_locale l ON l.owner_id=o.id WHERE o.id=?",owner()); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Missing owner");
            var value = object(); value.put("ownerId",owner()); value.put("zoneId",rows.getString(1)); value.put("version",rows.getLong(2));value.put("locale",rows.getString(3));
            value.put("holidayCountry",find("PROFILE_PREF",PROFILE_PREFERENCES).map(preferences->preferences.path("holidayCountry").asText(null)).orElse(null));return value;
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public ObjectNode updateProfile(String zone) {
        return updateProfile(zone,false,null);
    }
    public ObjectNode updateProfile(String zone,boolean updateLocale,String locale) {
        return updateProfile(zone,updateLocale,locale,false,null);
    }
    public ObjectNode updateProfile(String zone,boolean updateLocale,String locale,boolean updateHolidays,String holidayCountry) {
        var profile = profile(); long version = profile.get("version").asLong();
        if (update("UPDATE tk_owner SET zone_id=?,version=? WHERE id=? AND version=?",zone,version+1,owner(),version)!=1) throw new ApiFailure(412,"STALE_VERSION");
        if(updateLocale) {
            if(locale==null) update("DELETE FROM tk_locale WHERE owner_id=?",owner());
            else if(update("UPDATE tk_locale SET locale=? WHERE owner_id=?",locale,owner())==0) update("INSERT INTO tk_locale(owner_id,locale) VALUES(?,?)",owner(),locale);
        }
        if(updateHolidays) {
            // Owner-scoped preferences use the same internal JSON storage as
            // calendar subscriptions; no work records or schema changes are needed.
            update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type='PROFILE_PREF' AND id=?",owner(),PROFILE_PREFERENCES);
            if(holidayCountry!=null) {
                var preferences=object().put("id",PROFILE_PREFERENCES.toString()).put("ownerId",owner()).put("version",0).put("holidayCountry",holidayCountry);
                update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'PROFILE_PREF',?,0,?)",owner(),PROFILE_PREFERENCES,preferences.toString());
            }
        }
        change("PROFILE","UPDATED",owner()); return profile();
    }
    public Optional<JsonNode> find(String type, UUID id) {
        try (var statement = statement("SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",owner(),type,id); var rows = statement.executeQuery()) {
            if (!rows.next()) return Optional.empty(); return Optional.of(checkedPayload(type,rows.getObject(1,UUID.class),rows.getLong(2),rows.getString(3)));
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public List<JsonNode> list(String type) {
        List<JsonNode> list = new ArrayList<>();
        try (var statement = statement("SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? ORDER BY id",owner(),type); var rows = statement.executeQuery()) {
            while (rows.next()) list.add(checkedPayload(type,rows.getObject(1,UUID.class),rows.getLong(2),rows.getString(3)));
            return List.copyOf(list);
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    List<JsonNode> list(String type,UUID first,UUID last) {
        var result=new ArrayList<JsonNode>();
        try(var statement=statement("SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id>=? AND id<=? ORDER BY id",owner(),type,first,last);var rows=statement.executeQuery()) {
            while(rows.next())result.add(checkedPayload(type,rows.getObject(1,UUID.class),rows.getLong(2),rows.getString(3)));return List.copyOf(result);
        }catch(SQLException failure){throw databaseFailure(failure);}
    }
    private JsonNode checkedPayload(String type, UUID id, long version, String json) {
        JsonNode value = parse(json);
        if (!owner().equals(value.path("ownerId").asText()) || !id.toString().equals(value.path("id").asText()) || version!=value.path("version").asLong(-1))
            throw new IllegalStateException("Stored resource identity/version mismatch");
        if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            node.remove("workSessionId");
            if(type.equals("TASK")) {
                if(!node.has("status"))node.put("status",TaskStatus.NOT_STARTED.name());
                if(!node.has("scheduledDate"))node.putNull("scheduledDate");
                if(!node.has("completedAt"))node.putNull("completedAt");
                if(!node.has("checklistItems"))node.putArray("checklistItems");
                if(!node.has("start"))node.putNull("start");
                if(!node.has("due"))node.putNull("due");
                if(!node.has("priority"))node.put("priority",TaskPriority.UNSPECIFIED.name());
                if(!node.has("flagged"))node.put("flagged",false);
                if(!node.has("urgent"))node.put("urgent",false);
            }
        }
        return value;
    }
    /** Retire only obsolete metadata; keep IDs, versions, timers and recorded work untouched. */
    void retireSessionLinks() {
        try (var query=statement("SELECT id,payload FROM tk_resource WHERE owner_id=? AND entity_type='ACTIVITY'",owner());var rows=query.executeQuery()) {
            var changed=new java.util.LinkedHashMap<UUID,String>();
            while(rows.next()) {
                var node=(com.fasterxml.jackson.databind.node.ObjectNode)parse(rows.getString(2));
                if(node.remove("workSessionId")!=null) changed.put(rows.getObject(1,UUID.class),node.toString());
            }
            changed.forEach((id,json)->update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type='ACTIVITY' AND id=?",json,owner(),id));
        } catch(SQLException failure) {throw databaseFailure(failure);}
        update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='ACTIVITY' AND kind='workSession' AND to_type='WORK_SESSION'",owner());
    }
    public <T> T save(String type,T value) {
        var node = snapshot(owner(),value);
        if (type.equals("PROJECT") || type.equals("WORK_SESSION")) {
            var previous=find(type,uuid(node.get("id"))).orElse(null);
            node.put("clientId",previous!=null && previous.hasNonNull("clientId") ? previous.get("clientId").asText()
                    : creationClient.get()!=null ? creationClient.get().toString() : defaultClient().get("id").asText());
            if(type.equals("PROJECT") && previous!=null && previous.has("icon")) node.set("icon",previous.get("icon").deepCopy());
            if(type.equals("WORK_SESSION")) {
                // Web-only metadata survives domain timer/interval writes.
                node.set("labelIds",previous!=null && previous.has("labelIds") ? previous.get("labelIds").deepCopy() : array());
                if(previous!=null && previous.has("projectId")) node.set("projectId",previous.get("projectId").deepCopy());
            }
        }
        UUID id = uuid(node.get("id")); long version = node.get("version").asLong();
        if (version==0) update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",owner(),type,id,version,node.toString());
        else if (update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version,node.toString(),owner(),type,id,version-1)!=1)
            throw new ApiFailure(412,"STALE_VERSION");
        update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=?",owner(),type,id);
        if (value instanceof Task task) {
            task.projectId().ifPresent(project -> relation(type,id,"project","PROJECT",project.value()));
            task.parentTaskId().ifPresent(parent -> relation(type,id,"parent","TASK",parent.value()));
            Set<UUID> lists=new HashSet<>();task.externalReferences().forEach(reference -> reference.taskListId().ifPresent(lists::add));
            lists.forEach(list -> relation(type,id,"list","TASK_LIST",list));
            update("DELETE FROM tk_external_reference WHERE owner_id=? AND entity_type='TASK' AND task_id=?",owner(),id);
            task.externalReferences().forEach(reference -> {
                if (postgres()) update("INSERT INTO tk_external_reference(owner_id,provider_id,repository_id,external_id,entity_type,task_id,reference_key) VALUES(?,?,?,?,'TASK',?,?)",
                        owner(),reference.providerId(),reference.repositoryId(),reference.externalId(),id,
                        referenceKey(reference.providerId(),reference.repositoryId(),reference.externalId()));
                else update("INSERT INTO tk_external_reference(owner_id,provider_id,repository_id,external_id,entity_type,task_id) VALUES(?,?,?,?,'TASK',?)",
                        owner(),reference.providerId(),reference.repositoryId(),reference.externalId(),id);
            });
        }
        if (value instanceof TaskList list) relation(type,id,"connection","TASK_CONNECTION",list.connectionId());
        if (value instanceof Activity activity) {
            activity.task().ifPresent(task -> relation(type,id,"task","TASK",task.value()));
            activity.projectId().ifPresent(project -> relation(type,id,"project","PROJECT",project.value()));
            activity.labelIds().forEach(label -> relation(type,id,"label","LABEL",label.value()));
            if (activity.end().isPresent()) update("DELETE FROM tk_active WHERE owner_id=? AND activity_id=?",owner(),id);
            else if (active().isEmpty()) update("INSERT INTO tk_active(owner_id,entity_type,activity_id) VALUES(?,'ACTIVITY',?)",owner(),id);
            else if (!active().orElseThrow().get("id").asText().equals(id.toString())) throw ApiFailure.conflict();
        }
        if (node.hasNonNull("clientId")) relation(type,id,"client","CLIENT",uuid(node.get("clientId")));
        if(type.equals("WORK_SESSION")) {
            node.path("labelIds").forEach(label -> relation(type,id,"label","LABEL",uuid(label)));
            if(node.hasNonNull("projectId")) relation(type,id,"project","PROJECT",uuid(node.get("projectId")));
        }
        return value;
    }
    private JsonNode defaultClient() {
        UUID id=UUID.nameUUIDFromBytes(("timekeeper-default-client:"+owner()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return find("CLIENT",id).orElseGet(() -> {
            var node=object();node.put("id",id.toString());node.put("ownerId",owner());node.put("version",0);
            node.put("name","General");node.put("color","#2563eb");writeClient(node);change("CLIENT","CREATED",id.toString());return node;
        });
    }
    void migrateClientDetails() {
        for(String type:List.of("PROJECT","WORK_SESSION")) for(var old:list(type)) {
            if(old.hasNonNull("clientId")) continue;
            var node=((ObjectNode)old).deepCopy();long version=node.get("version").asLong();
            node.put("clientId",defaultClient().get("id").asText());
            node.put("version",version+1);
            if(update("UPDATE tk_resource SET payload=?,version=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",node.toString(),version+1,owner(),type,uuid(node.get("id")),version)!=1) throw new ApiFailure(412,"STALE_VERSION");
            relation(type,uuid(node.get("id")),"client","CLIENT",uuid(node.get("clientId")));
            change(type,"UPDATED",node.get("id").asText());
        }
    }
    /** Owner-locked, repeatable payload migration; interval and catalogue links stay intact. */
    void migrateWorkSessionDescriptions() {
        var migrated=new java.util.HashMap<UUID,ImportProvenance.DescriptionMigration>();
        for(var old:list("WORK_SESSION")) {
            if(!old.has("title")) continue;
            var node=((ObjectNode)old).deepCopy();long version=node.path("version").asLong();
            node.put("summary",WorkSessionDescriptions.summary(old));node.remove("title");node.put("version",version+1);
            if(update("UPDATE tk_resource SET payload=?,version=? WHERE owner_id=? AND entity_type='WORK_SESSION' AND id=? AND version=?",
                    node.toString(),version+1,owner(),uuid(node.get("id")),version)!=1) throw new ApiFailure(412,"STALE_VERSION");
            migrated.put(uuid(node.get("id")),new ImportProvenance.DescriptionMigration(version,hash(canonical(old).toString()),node));
            change("WORK_SESSION","UPDATED",node.path("id").asText());
        }
        ImportProvenance.workSessionDescriptions(this,migrated);
    }
    JsonNode createClient(JsonNode input) {
        var node=((ObjectNode)input).deepCopy();node.put("id",UUID.randomUUID().toString());node.put("ownerId",owner());node.put("version",0);
        writeClient(node);change("CLIENT","CREATED",node.get("id").asText());return node;
    }
    JsonNode updateClient(UUID id,long version,JsonNode input) {
        var node=((ObjectNode)input).deepCopy();node.put("id",id.toString());node.put("ownerId",owner());node.put("version",version+1);
        writeClient(node);change("CLIENT","UPDATED",id.toString());return node;
    }
    private void writeClient(ObjectNode node) {
        long version=node.get("version").asLong();UUID id=uuid(node.get("id"));
        if(version==0) update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'CLIENT',?,0,?)",owner(),id,node.toString());
        else if(update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type='CLIENT' AND id=? AND version=?",version,node.toString(),owner(),id,version-1)!=1) throw new ApiFailure(412,"STALE_VERSION");
    }
    /** Completes a domain mutation in the same owner-locked transaction and version/event. */
    JsonNode clientDetails(String type,UUID id,JsonNode input) {
        var node=((ObjectNode)find(type,id).orElseThrow(ApiFailure::notFound)).deepCopy();
        if(input.has("clientId")) {
            UUID client=uuid(input.get("clientId"));find("CLIENT",client).orElseThrow(ApiFailure::notFound);node.put("clientId",client.toString());
        }
        if(type.equals("PROJECT") && input.has("icon")) node.set("icon",input.get("icon").deepCopy());
        if(type.equals("PROJECT") && input.has("clientId")) {
            // Reassign sessions explicitly before moving a project to another client.
            // Historical client totals must never change as a side effect of editing a project.
            var previous=find(type,id).orElseThrow(ApiFailure::notFound);
            if(!node.get("clientId").equals(previous.get("clientId")) && sessionUsesProject(id)) throw ApiFailure.conflict();
        }
        if(type.equals("WORK_SESSION")) {
            if(input.has("projectId")) node.set("projectId",input.get("projectId").deepCopy());
            if(node.hasNonNull("projectId")) {
                var project=find("PROJECT",uuid(node.get("projectId"))).orElseThrow(ApiFailure::notFound);
                if(input.has("clientId") && !input.get("clientId").equals(project.get("clientId"))) throw ApiFailure.validation();
                node.set("clientId",project.get("clientId").deepCopy());
            }
            update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='WORK_SESSION' AND from_id=? AND kind='project'",owner(),id);
            if(node.hasNonNull("projectId")) relation(type,id,"project","PROJECT",uuid(node.get("projectId")));
        }
        if(type.equals("WORK_SESSION") && input.has("labelIds")) {
            var assigned=node.putArray("labelIds");
            labels(input.get("labelIds")).stream().map(label->label.value()).sorted().forEach(label->{
                find("LABEL",label).orElseThrow(ApiFailure::notFound);assigned.add(label.toString());
            });
            update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='WORK_SESSION' AND from_id=? AND kind='label'",owner(),id);
            assigned.forEach(label->relation(type,id,"label","LABEL",uuid(label)));
        }
        if(update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",node.toString(),owner(),type,id,node.get("version").asLong())!=1) throw new ApiFailure(412,"STALE_VERSION");
        update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=? AND kind='client'",owner(),type,id);
        relation(type,id,"client","CLIENT",uuid(node.get("clientId")));return node;
    }
    private boolean sessionUsesProject(UUID project) {
        try(var query=statement("SELECT 1 FROM tk_relation WHERE owner_id=? AND from_type='WORK_SESSION' AND kind='project' AND to_type='PROJECT' AND to_id=? FETCH FIRST 1 ROW ONLY",owner(),project);var rows=query.executeQuery()) {return rows.next();}
        catch(SQLException failure) {throw databaseFailure(failure);}
    }
    private void relation(String fromType,UUID fromId,String kind,String toType,UUID toId) {
        update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,?,?,?,?,?)",owner(),fromType,fromId,kind,toType,toId);
    }
    public void delete(String type,UUID id) {
        // Older replicas may have written a legacy link after startup cleanup.
        if(type.equals("WORK_SESSION")) update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='ACTIVITY' AND kind='workSession' AND to_type='WORK_SESSION' AND to_id=?",owner(),id);
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
                event -> change(event.entityType().name(),event.changeType().name(),event.entityId())),workSessionService());
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
                return all("ACTIVITY",Activity.class).stream().filter(value -> task.isEmpty() || task.get().equals(value.taskId()))
                        .filter(value -> value.start().isBefore(to) && value.end().orElse(now()).isAfter(from)).toList();
            }
            public boolean existsByTask(TaskId id) { check(); return related("task","TASK",id.value()); }
            public boolean existsByLabel(LabelId id) { check(); return related("label","LABEL",id.value()); }
            public Activity save(Activity value) { check(); return ServerStore.this.save("ACTIVITY",value); }
            public Activity saveDailyPart(Activity value,Activity prior,boolean unchangedWork) {
                var saved=save(value);ImportProvenance.dailyPart(ServerStore.this,prior,saved,unchangedWork);
                if(prior!=null)new CalendarSubscriptions(ServerStore.this).inherit(prior.id().value(),saved.id().value());return saved;
            }
            public void delete(ActivityId id) { check(); ServerStore.this.delete("ACTIVITY",id.value()); }
        }; }
    }
}
