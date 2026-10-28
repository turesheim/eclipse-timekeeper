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
    enum State { PREPARING, UPLOADING, VALIDATING, REVIEW, READY, FAILED, INTERRUPTED, EXPIRED }
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
    static Status status(Connection connection,UUID id) {return read(connection,id,false).status();}
    static Status advance(Connection connection,Lease lease,State expected,State next,Instant now) {
        lockControl(connection);var row=read(connection,lease.id(),true);var value=row.payload();
        requireLease(value,lease,expected,now);
        boolean allowed=switch(expected) {
            case PREPARING -> next==State.READY||next==State.FAILED;
            case UPLOADING -> next==State.VALIDATING||next==State.FAILED;
            case VALIDATING -> next==State.REVIEW||next==State.FAILED;
            default -> false;
        };
        if(!allowed)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        value.put("state",next.name());
        if(next==State.VALIDATING)value.put("leaseUntil",now.plus(LEASE).toString());
        else value.remove(List.of("worker","leaseUntil"));
        update(connection,row,now,value);audit(connection,lease.id(),row.actor(),now,row.status().type(),next);
        return status(connection,lease.id());
    }
    static void renew(Connection connection,Lease lease,State expected,Instant now) {
        lockControl(connection);var row=read(connection,lease.id(),true);requireLease(row.payload(),lease,expected,now);
        row.payload().put("leaseUntil",now.plus(LEASE).toString());update(connection,row,now,row.payload());
    }
    /** Explicitly discard a completed artifact or a restore review, retaining its audit. */
    static Status expire(Connection connection,UUID id,Instant now) {
        lockControl(connection);var row=read(connection,id,true);var previous=row.status().state();
        if(previous!=State.READY&&previous!=State.REVIEW)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        row.payload().remove("download");row.payload().put("state",State.EXPIRED.name());update(connection,row,now,row.payload());
        audit(connection,id,row.actor(),now,row.status().type(),State.EXPIRED);return status(connection,id);
    }
    static int interruptAbandoned(Connection connection,Instant now) {
        lockControl(connection);var abandoned=new ArrayList<UUID>();
        try(var statement=query(connection,"SELECT id,payload FROM tk_instance WHERE kind='OPERATION'");var rows=statement.executeQuery()) {
            while(rows.next()) {
                var value=(ObjectNode)parse(rows.getString(2));
                if(value.has("leaseUntil")&&!Instant.parse(value.path("leaseUntil").asText()).isAfter(now))abandoned.add(UUID.fromString(rows.getString(1)));
            }
        }catch(SQLException failure){throw unavailable();}
        for(UUID id:abandoned) {
            var row=read(connection,id,true);row.payload().put("state",State.INTERRUPTED.name()).remove(List.of("worker","leaseUntil"));
            update(connection,row,now,row.payload());audit(connection,id,row.actor(),now,row.status().type(),State.INTERRUPTED);
        }
        return abandoned.size();
    }
    private static boolean terminal(State state){return Set.of(State.READY,State.FAILED,State.INTERRUPTED,State.EXPIRED).contains(state);}
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
