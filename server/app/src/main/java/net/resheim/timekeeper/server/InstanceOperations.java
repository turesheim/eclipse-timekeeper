package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Installation-wide durable work receipts. Callers own authorization and transactions. */
final class InstanceOperations {
    private InstanceOperations() { }
    private static final UUID CONTROL=new UUID(0,0);
    private static final Duration LEASE=Duration.ofMinutes(10);
    enum Type { BACKUP, RESTORE }
    enum State { PREPARING, UPLOADING, VALIDATING, REVIEW, READY, FAILED, INTERRUPTED, EXPIRED, RESTORED }
    /** Internal fencing capability, never serialize as a response or log it. */
    record Lease(UUID id,UUID worker) {
        @Override public String toString(){return "Private instance-operation lease";}
    }
    record Status(UUID id,Type type,State state,long version,Instant updatedAt) { }

    /** Fail closed when replicas use different mounts for the same installation. */
    static void bindStorage(Connection connection,UUID root) {
        if(root==null)throw ApiFailure.validation();lockControl(connection);
        try(var statement=query(connection,"SELECT payload FROM tk_instance WHERE id=?",CONTROL);var rows=statement.executeQuery()) {
            if(!rows.next())throw unavailable();var value=(ObjectNode)parse(rows.getString(1));
            if(value.has("artifactRootId")) {
                if(!value.path("artifactRootId").asText().equals(root.toString()))throw new ApiFailure(503,"INSTANCE_STORAGE_MISMATCH");
            } else {
                value.put("artifactRootId",root.toString());
                execute(connection,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",value.toString(),CONTROL);
            }
        }catch(SQLException failure){throw unavailable();}
    }
    static Lease begin(Connection connection,String actor,Type type,Instant now) {
        if(actor==null||actor.isBlank()||actor.length()>400||type==null||now==null)throw ApiFailure.validation();
        lockControl(connection);
        // Recovery changes only abandoned work metadata, never application data.
        interruptAbandoned(connection,now);
        try(var statement=query(connection,"SELECT payload FROM tk_instance WHERE kind='OPERATION'");var rows=statement.executeQuery()) {
            while(rows.next())if(!terminal(state((ObjectNode)parse(rows.getString(1)))))throw new ApiFailure(409,"INSTANCE_OPERATION_IN_PROGRESS");
        }catch(SQLException failure){throw unavailable();}
        var lease=new Lease(UUID.randomUUID(),UUID.randomUUID());
        var value=object().put("type",type.name()).put("state",type==Type.BACKUP?State.PREPARING.name():State.UPLOADING.name())
            .put("worker",lease.worker().toString()).put("leaseUntil",now.plus(LEASE).toString());
        insert(connection,lease.id(),"OPERATION",lease.id(),actor,now,value);
        audit(connection,lease.id(),actor,now,type,state(value));return lease;
    }
    static Lease beginUpload(Connection connection,TokenRegistry.Principal principal,long bytes,String digest,Instant now) {
        var lease=begin(connection,principal.owner(),Type.RESTORE,now);var row=read(connection,lease.id(),true);
        row.payload().putObject("upload").put("session",principal.digest()).put("bytes",bytes).put("sha256",digest);update(connection,row,now,row.payload());return lease;
    }
    private static Row uploadRow(Connection connection,UUID id,TokenRegistry.Principal principal) {
        var row=read(connection,id,true);
        if(row.status().type()!=Type.RESTORE||!row.actor().equals(principal.owner())||!row.payload().path("upload").path("session").asText().equals(principal.digest()))throw new ApiFailure(403,"FORBIDDEN");
        return row;
    }
    static Lease uploadLease(Connection connection,UUID id,TokenRegistry.Principal principal,Instant now) {
        lockControl(connection);var row=uploadRow(connection,id,principal);
        if(row.status().state()!=State.UPLOADING)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        var lease=new Lease(id,UUID.fromString(row.payload().path("worker").asText()));requireLease(row.payload(),lease,State.UPLOADING,now);return lease;
    }
    static ObjectNode uploadInfo(Connection connection,UUID id,TokenRegistry.Principal principal) {
        var row=uploadRow(connection,id,principal);var result=object().put("bytes",row.payload().path("upload").path("bytes").asLong()).put("sha256",row.payload().path("upload").path("sha256").asText());
        if(row.payload().has("validationCode"))result.set("validationCode",row.payload().get("validationCode"));
        if(row.payload().has("review"))result.set("review",row.payload().get("review").deepCopy());return result;
    }
    static void rejectValidation(Connection connection,Lease lease,Instant now) {rejectValidation(connection,lease,now,false);}
    static void rejectValidation(Connection connection,Lease lease,Instant now,boolean authentication) {
        advance(connection,lease,State.VALIDATING,State.UPLOADING,now);var row=read(connection,lease.id(),true);
        row.payload().put("validationCode",authentication?"INVALID_INSTANCE_AUTHENTICATION":"INVALID_INSTANCE_BACKUP");update(connection,row,now,row.payload());
    }
    static void finishReview(Connection connection,Lease lease,ObjectNode review,Instant now) {
        advance(connection,lease,State.VALIDATING,State.REVIEW,now);var row=read(connection,lease.id(),true);
        row.payload().remove("validationCode");row.payload().set("review",review.deepCopy());update(connection,row,now,row.payload());
    }
    static ObjectNode stagingInfo(Connection c,UUID id,TokenRegistry.Principal principal) {
        var row=uploadRow(c,id,principal);String phase=row.payload().path("restorePhase").asText();
        var result=object().put("id",id.toString()).put("operationVersion",row.status().version()).put("phase",switch(phase){case "STAGING"->row.status().state()==State.REVIEW?"PREPARING":"FAILED";case "STAGED"->row.status().state()==State.REVIEW?"READY":"NONE";case "STAGE_FAILED"->"FAILED";default->"NONE";});
        if(row.payload().has("stageSummary"))result.set("target",row.payload().get("stageSummary").deepCopy());
        if(row.payload().has("restoreStageGeneration"))result.set("generation",row.payload().get("restoreStageGeneration"));return result;
    }
    static Lease beginStaging(Connection c,UUID id,TokenRegistry.Principal principal,long version,UUID generation,ObjectNode summary,Instant now) {
        lockControl(c);var row=uploadRow(c,id,principal);
        if(row.status().version()!=version)throw new ApiFailure(412,"STALE_VERSION");
        if(row.status().state()!=State.REVIEW||!Set.of("","STAGE_FAILED").contains(row.payload().path("restorePhase").asText()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        var lease=new Lease(id,UUID.randomUUID());row.payload().put("restorePhase","STAGING").put("restoreStageGeneration",generation.toString()).put("worker",lease.worker().toString()).put("leaseUntil",now.plus(LEASE).toString());row.payload().set("stageSummary",summary.deepCopy());
        update(c,row,now,row.payload());return lease;
    }
    /** Private renewal does not invalidate the version reserved for the published stage. */
    static void renewStaging(Connection c,Lease lease,Instant now) {
        lockControl(c);var row=read(c,lease.id(),true);requireStaging(row,lease,now);row.payload().put("leaseUntil",now.plus(LEASE).toString());
        execute(c,"UPDATE tk_instance SET payload=? WHERE id=? AND version=?",row.payload().toString(),lease.id(),row.status().version());
    }
    static void finishStaging(Connection c,Lease lease,long version,UUID generation,String digest,Instant now) {
        lockControl(c);var row=read(c,lease.id(),true);requireStaging(row,lease,now);
        if(row.status().version()+1!=version||!row.payload().path("restoreStageGeneration").asText().equals(generation.toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        row.payload().put("restorePhase","STAGED");row.payload().putObject("restoreIntent").put("generation",generation.toString()).put("sha256",digest);row.payload().remove(List.of("worker","leaseUntil"));update(c,row,now,row.payload());
        insert(c,UUID.randomUUID(),"AUDIT",lease.id(),row.actor(),now,object().put("type","RESTORE").put("state","REVIEW").put("action","RESTORE_STAGED"));
    }
    static void failStaging(Connection c,Lease lease,Instant now) {
        lockControl(c);var row=read(c,lease.id(),true);requireStaging(row,lease,now);row.payload().put("restorePhase","STAGE_FAILED").remove(List.of("worker","leaseUntil"));update(c,row,now,row.payload());
    }
    private static void requireStaging(Row row,Lease lease,Instant now) {
        requireLease(row.payload(),lease,State.REVIEW,now);if(!row.payload().path("restorePhase").asText().equals("STAGING"))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
    }
    static void issueDownload(Connection connection,UUID id,TokenRegistry.Principal principal,String digest,Instant verifiedAt,Instant expires,long bytes,Instant now) {
        lockControl(connection);var row=read(connection,id,true);readyBackup(row);
        if(verifiedAt.isBefore(now.minusSeconds(300))||verifiedAt.isAfter(now.plusSeconds(5))||!expires.isAfter(now)||expires.isAfter(now.plusSeconds(120))||bytes<1)throw new ApiFailure(403,"INSTANCE_DOWNLOAD_REQUIRED");
        row.payload().putObject("download").put("digest",digest).put("owner",principal.owner()).put("session",principal.digest()).put("verifiedAt",verifiedAt.toString()).put("expiresAt",expires.toString()).put("bytes",bytes);
        update(connection,row,now,row.payload());downloadAudit(connection,id,principal.owner(),now,"DOWNLOAD_AUTHORIZED");
    }
    static ObjectNode consumeDownload(Connection connection,UUID id,TokenRegistry.Principal principal,String digest,Instant now) {
        lockControl(connection);var row=read(connection,id,true);readyBackup(row);var grant=row.payload().path("download");
        if(!grant.isObject()||!grant.path("owner").asText().equals(principal.owner())||!grant.path("session").asText().equals(principal.digest())
            ||!java.security.MessageDigest.isEqual(grant.path("digest").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII),digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            ||!Instant.parse(grant.path("expiresAt").asText()).isAfter(now)||Instant.parse(grant.path("verifiedAt").asText()).isBefore(now.minusSeconds(300)))throw new ApiFailure(403,"INSTANCE_DOWNLOAD_REQUIRED");
        var result=((ObjectNode)grant).deepCopy();row.payload().remove("download");update(connection,row,now,row.payload());downloadAudit(connection,id,principal.owner(),now,"DOWNLOAD_STARTED");
        result.remove(java.util.List.of("digest","owner","session","expiresAt"));return result;
    }
    private static void readyBackup(Row row) {if(row.status().type()!=Type.BACKUP||row.status().state()!=State.READY)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");}
    private static void downloadAudit(Connection connection,UUID operation,String actor,Instant now,String action) {
        insert(connection,UUID.randomUUID(),"AUDIT",operation,actor,now,object().put("type",Type.BACKUP.name()).put("state",State.READY.name()).put("action",action));
    }
    /** Bounded metadata for recovery after navigation or a lost start reply. */
    static com.fasterxml.jackson.databind.node.ArrayNode recent(Connection connection,TokenRegistry.Principal principal) {
        var items=JsonValues.MAPPER.createArrayNode();
        try(var statement=query(connection,"SELECT id,version,actor_owner,occurred_at,payload FROM tk_instance WHERE kind='OPERATION' ORDER BY CAST(occurred_at AS TIMESTAMP WITH TIME ZONE) DESC,id DESC FETCH FIRST 50 ROWS ONLY");var rows=statement.executeQuery()) {
            while(rows.next()) {
                var payload=(ObjectNode)parse(rows.getString(5));
                var operation=new Status(UUID.fromString(rows.getString(1)),Type.valueOf(payload.path("type").asText()),state(payload),rows.getLong(2),Instant.parse(rows.getString(4)));
                boolean own=operation.type()==Type.RESTORE&&principal.owner().equals(rows.getString(3))&&principal.digest()!=null&&principal.digest().equals(payload.path("upload").path("session").asText());
                var item=object().put("canAccessUpload",own);item.set("operation",InstanceRestoreUploads.representation(operation));items.add(item);
            }
            return items;
        }catch(SQLException failure){throw unavailable();}
    }
    static Status status(Connection connection,UUID id) {return read(connection,id,false).status();}
    static Status advance(Connection connection,Lease lease,State expected,State next,Instant now) {
        lockControl(connection);var row=read(connection,lease.id(),true);var value=row.payload();
        requireLease(value,lease,expected,now);
        boolean allowed=switch(expected) {
            case PREPARING -> next==State.READY||next==State.FAILED;
            case UPLOADING -> next==State.VALIDATING||next==State.FAILED;
            case VALIDATING -> next==State.REVIEW||next==State.FAILED||next==State.UPLOADING;
            default -> false;
        };
        if(!allowed)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        value.put("state",next.name());if(next==State.VALIDATING)value.remove("validationCode");
        if(next==State.VALIDATING||next==State.UPLOADING)value.put("leaseUntil",now.plus(LEASE).toString());
        else value.remove(List.of("worker","leaseUntil"));
        update(connection,row,now,value);audit(connection,lease.id(),row.actor(),now,row.status().type(),next);
        return status(connection,lease.id());
    }
    static void renew(Connection connection,Lease lease,State expected,Instant now) {
        lockControl(connection);var row=read(connection,lease.id(),true);requireLease(row.payload(),lease,expected,now);
        row.payload().put("leaseUntil",now.plus(LEASE).toString());update(connection,row,now,row.payload());
    }
    /** Explicitly discard a completed artifact or a restore review, retaining its audit. */
    static Status expire(Connection connection,UUID id,String actor,Instant now) {
        lockControl(connection);var row=read(connection,id,true);var previous=row.status().state();
        if(!Set.of(State.READY,State.REVIEW,State.UPLOADING,State.FAILED,State.INTERRUPTED).contains(previous))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        row.payload().remove(List.of("download","worker","leaseUntil"));row.payload().put("state",State.EXPIRED.name());update(connection,row,now,row.payload());
        audit(connection,id,actor,now,row.status().type(),State.EXPIRED);return status(connection,id);
    }
    static int interruptAbandoned(Connection connection,Instant now) {
        lockControl(connection);var abandoned=new ArrayList<UUID>();
        try(var statement=query(connection,"SELECT id,payload FROM tk_instance WHERE kind='OPERATION'");var rows=statement.executeQuery()) {
            while(rows.next()) {
                var value=(ObjectNode)parse(rows.getString(2));
                if(!terminal(state(value))&&value.has("leaseUntil")&&!Instant.parse(value.path("leaseUntil").asText()).isAfter(now))abandoned.add(UUID.fromString(rows.getString(1)));
            }
        }catch(SQLException failure){throw unavailable();}
        for(UUID id:abandoned) {
            var row=read(connection,id,true);row.payload().put("state",State.INTERRUPTED.name()).remove(List.of("worker","leaseUntil"));
            update(connection,row,now,row.payload());audit(connection,id,row.actor(),now,row.status().type(),State.INTERRUPTED);
        }
        return abandoned.size();
    }
    private static boolean terminal(State state){return Set.of(State.READY,State.FAILED,State.INTERRUPTED,State.EXPIRED,State.RESTORED).contains(state);}
    private static State state(ObjectNode value){return State.valueOf(value.path("state").asText());}
    private static void requireLease(ObjectNode value,Lease lease,State expected,Instant now) {
        if(state(value)!=expected||!value.path("worker").asText().equals(lease.worker().toString())||!Instant.parse(value.path("leaseUntil").asText()).isAfter(now))throw new ApiFailure(409,"INSTANCE_OPERATION_LEASE_LOST");
    }
    private record Row(Status status,String actor,ObjectNode payload) { }
    private static Row read(Connection connection,UUID id,boolean locked) {
        try(var statement=query(connection,"SELECT version,actor_owner,occurred_at,payload FROM tk_instance WHERE id=? AND kind='OPERATION'"+(locked?" FOR UPDATE":""),id);var rows=statement.executeQuery()) {
            if(!rows.next())throw ApiFailure.notFound();var value=(ObjectNode)parse(rows.getString(4));
            return new Row(new Status(id,Type.valueOf(value.path("type").asText()),state(value),rows.getLong(1),Instant.parse(rows.getString(3))),rows.getString(2),value);
        }catch(SQLException failure){throw unavailable();}
    }
    private static void lockControl(Connection connection) {
        try {
            if(connection.getAutoCommit())throw unavailable();
            try(var statement=query(connection,"SELECT version FROM tk_instance WHERE id=? AND kind='CONTROL' FOR UPDATE",CONTROL);var rows=statement.executeQuery()) {if(!rows.next())throw unavailable();}
        }catch(SQLException failure){throw unavailable();}
    }
    private static void update(Connection connection,Row row,Instant now,ObjectNode value) {
        if(row.status().version()>=9007199254740991L)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        try(var statement=query(connection,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=? AND version=?",now.toString(),value.toString(),row.status().id(),row.status().version())) {
            if(statement.executeUpdate()!=1)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        }catch(SQLException failure){throw unavailable();}
    }
    private static void audit(Connection connection,UUID operation,String actor,Instant now,Type type,State state) {
        insert(connection,UUID.randomUUID(),"AUDIT",operation,actor,now,object().put("type",type.name()).put("state",state.name()));
    }
    private static void insert(Connection connection,UUID id,String kind,UUID operation,String actor,Instant now,ObjectNode value) {
        try(var statement=query(connection,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,?,1,?,?,?,?)",id,kind,operation,actor,now.toString(),value.toString())) {statement.executeUpdate();}
        catch(SQLException failure){throw unavailable();}
    }
    private static ApiFailure unavailable(){return new ApiFailure(503,"INSTANCE_OPERATIONS_UNAVAILABLE");}
}
