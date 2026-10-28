package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Offline database phase only. Files must be recovered before clearing the pending gate. */
final class InstanceRestoreDatabase {
    private InstanceRestoreDatabase() { }
    private static final UUID CONTROL=new UUID(0,0);
    private static final long MAX_VERSION=9007199254740991L;
    record Deployment(String packageId,String publicOrigin,String passkeyRpId,boolean passkeysEnabled) {
        Deployment {
            if(packageId==null||!packageId.matches("[a-z][a-z0-9_-]{0,63}")||publicOrigin==null||passkeyRpId==null)throw ApiFailure.validation();
            if(!publicOrigin.equals("NONE"))RecoveryOrigin.validate(publicOrigin);
            PasskeyRealm.rp(publicOrigin,passkeyRpId,passkeysEnabled);
        }
    }
    record Applied(UUID generation,long originEpoch) { }
    private record Evidence(UUID id,String kind,long version,UUID operation,String actor,String occurred,String payload) { }

    /** Never commits. A failure rolls back this whole phase, including post-copy changes. */
    static Applied apply(Connection target,InstanceRestoreFence.Exclusive exclusive,InstanceBackupArchive.Verified archive,
            Deployment deployment,String actor,UUID operation,long expectedVersion,UUID root,UUID generation,Instant now) throws SQLException,IOException {
        if(exclusive==null)throw new SQLException("Offline restore requires exclusivity");
        exclusive.require(target);
        if(target.getAutoCommit()||target.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE
                ||archive==null||deployment==null||actor==null||operation==null||root==null||generation==null||generation.equals(CONTROL)||now==null)throw ApiFailure.validation();
        var savepoint=target.setSavepoint();
        try {
            if(!InstanceRestoreTarget.inspect(target,actor).databaseEligible())throw new ApiFailure(409,"INSTANCE_TARGET_NOT_EMPTY");
            var evidence=evidence(target);var ids=new HashSet<UUID>();evidence.forEach(row->ids.add(row.id()));
            Evidence selected=evidence.stream().filter(row->row.id().equals(operation)&&row.kind().equals("OPERATION")).findFirst().orElseThrow(ApiFailure::notFound);
            var receipt=(ObjectNode)parse(selected.payload());
            if(!selected.actor().equals(actor)||!receipt.path("type").asText().equals("RESTORE")||!receipt.path("state").asText().equals("REVIEW"))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(selected.version()!=expectedVersion)throw new ApiFailure(412,"STALE_VERSION");
            if(expectedVersion>=MAX_VERSION)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            var control=control(target);
            if(!control.path("artifactRootId").asText().equals(root.toString()))throw new ApiFailure(503,"INSTANCE_STORAGE_MISMATCH");
            long previousEpoch=epoch(target);long[] sourceEpoch={0};
            verifyArchive(archive);
            InstanceConfiguration.validate(archive.configuration().document(),archive.configuration().files().keySet());
            InstanceDatabaseImage.preflight(archive.database(),archive.directory(),source->{
                InstanceAuthenticationPreflight.inspect(source);sourceEpoch[0]=epoch(source);
                try(var statement=source.createStatement();var rows=statement.executeQuery("SELECT version FROM tk_instance WHERE kind='CONTROL'")){if(!rows.next()||rows.getLong(1)>=MAX_VERSION)throw new IOException("INSTANCE_RESTORE_VERSION_LIMIT");}
                var settings=ServiceSettings.forRestore(source);if(settings.version()>=MAX_VERSION)throw new IOException("INSTANCE_RESTORE_VERSION_LIMIT");
                sourceEpoch[0]=Math.max(sourceEpoch[0],Math.max(settings.saved().path("originEpoch").asLong(),settings.effective().path("originEpoch").asLong()));
                try(var statement=source.createStatement();var rows=statement.executeQuery("SELECT id,kind,version,payload FROM tk_instance WHERE kind='OPERATION' OR kind='AUDIT'")) {
                    while(rows.next()) {
                        if(ids.contains(UUID.fromString(rows.getString(1))))throw new IOException("INSTANCE_RESTORE_EVIDENCE_COLLISION");
                        if(rows.getLong(3)>=MAX_VERSION)throw new IOException("INSTANCE_RESTORE_VERSION_LIMIT");
                        if(rows.getString(2).equals("OPERATION")) {
                            var payload=parse(rows.getString(4));InstanceOperations.Type.valueOf(payload.path("type").asText());InstanceOperations.State.valueOf(payload.path("state").asText());
                        }
                    }
                }
            });
            long epoch=Math.max(previousEpoch,sourceEpoch[0]);if(epoch>=MAX_VERSION)throw new IOException("INSTANCE_RESTORE_EPOCH_LIMIT");epoch++;
            InstanceDatabaseImage.replace(target,archive.database());
            expireImportedOperations(target,now);
            for(var row:evidence)execute(target,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,?,?,?,?,?,?)",row.id(),row.kind(),row.version(),row.operation(),row.actor(),row.occurred(),row.payload());
            var restoredControl=control(target).put("artifactRootId",root.toString()).put("restoreGeneration",generation.toString()).put("restorePending",true);
            if(control.has("restoreIntent"))restoredControl.set("restoreIntent",control.get("restoreIntent").deepCopy());
            restoredControl.putObject("restore").put("operationId",operation.toString()).put("archiveId",archive.manifest().path("archiveId").asText()).put("phase","DATABASE_APPLIED").put("packageId",deployment.packageId());
            execute(target,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",restoredControl.toString(),CONTROL);
            remapAuthentication(target,deployment,epoch);
            receipt.remove(List.of("download","worker","leaseUntil","upload"));
            receipt.put("restorePhase","DATABASE_APPLIED").put("restoreGeneration",generation.toString());
            execute(target,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),receipt.toString(),operation);
            execute(target,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),operation,actor,now.toString(),object().put("type","RESTORE").put("state","REVIEW").put("action","DATABASE_APPLIED").toString());
            verifyArchive(archive);
            ServerSchema.validate(target);InstanceAuthenticationPreflight.inspect(target);
            target.releaseSavepoint(savepoint);return new Applied(generation,epoch);
        }catch(SQLException|IOException|RuntimeException failure) {
            try{target.rollback(savepoint);}catch(SQLException rollback){throw new SQLException("Instance database phase rollback failed","08006");}
            throw failure;
        }
    }
    private static MessageDigest digest() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException();}}
    static void verifyArchive(InstanceBackupArchive.Verified archive) throws IOException {
        if(!Files.isRegularFile(archive.database(),LinkOption.NOFOLLOW_LINKS))throw new IOException("INVALID_INSTANCE_BACKUP");
        var digest=digest();long bytes;
        try(var input=new DigestInputStream(Files.newInputStream(archive.database()),digest)){bytes=input.transferTo(OutputStream.nullOutputStream());}
        verifyEntry(archive,"database.json",bytes,digest.digest());
        byte[] configuration=MAPPER.writeValueAsBytes(archive.configuration().document());
        try{verifyEntry(archive,"configuration.json",configuration.length,digest().digest(configuration));}finally{Arrays.fill(configuration,(byte)0);}
        for(var file:archive.configuration().files().entrySet())verifyEntry(archive,file.getKey(),file.getValue().length,digest().digest(file.getValue()));
    }
    private static void verifyEntry(InstanceBackupArchive.Verified archive,String name,long bytes,byte[] digest) throws IOException {
        var index=archive.manifest().path("files").path(name);
        if(!index.path("bytes").isIntegralNumber()||index.path("bytes").asLong()!=bytes||!index.path("sha256").asText().equals(HexFormat.of().formatHex(digest)))throw new IOException("INVALID_INSTANCE_BACKUP");
    }
    private static long epoch(Connection c) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT epoch FROM tk_recovery_control WHERE id=1")){if(!r.next())throw new SQLException("Missing authentication control");return r.getLong(1);}}
    private static ObjectNode control(Connection c) throws SQLException {try(var s=query(c,"SELECT payload FROM tk_instance WHERE id=?",CONTROL);var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing instance control");return (ObjectNode)parse(r.getString(1));}}
    private static List<Evidence> evidence(Connection c) throws SQLException,IOException {
        var result=new ArrayList<Evidence>();long size=0;
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id,kind,version,operation_id,actor_owner,occurred_at,payload FROM tk_instance WHERE kind<>'CONTROL'")) {
            while(r.next()) {
                String payload=r.getString(7);size+=payload.length();if(result.size()>=10000||size>8_000_000)throw new IOException("INSTANCE_RESTORE_EVIDENCE_LIMIT");
                result.add(new Evidence(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3),UUID.fromString(r.getString(4)),r.getString(5),r.getString(6),payload));
            }
        }
        return result;
    }
    private static void expireImportedOperations(Connection c,Instant now) throws SQLException {
        try(var s=c.createStatement()) {
            s.setFetchSize(128);try(var r=s.executeQuery("SELECT id,payload FROM tk_instance WHERE kind='OPERATION'")) {
            while(r.next()) {
                var payload=(ObjectNode)parse(r.getString(2));var state=InstanceOperations.State.valueOf(payload.path("state").asText());
                // Imported artifacts and their capabilities are deployment-local, even for completed backups.
                if(!Set.of(InstanceOperations.State.FAILED,InstanceOperations.State.INTERRUPTED,InstanceOperations.State.EXPIRED).contains(state))payload.put("state","EXPIRED");
                payload.remove(List.of("download","worker","leaseUntil","upload"));
                execute(c,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),payload.toString(),UUID.fromString(r.getString(1)));
            }}
        }
    }
    private static void remapAuthentication(Connection c,Deployment deployment,long epoch) {
        var settings=ServiceSettings.forRestore(c);var saved=settings.saved().deepCopy();var effective=settings.effective().deepCopy();
        for(var value:List.of(saved,effective)) {
            value.put("publicOrigin",deployment.publicOrigin().equals("NONE")?null:deployment.publicOrigin()).put("originEpoch",epoch)
                .put("passkeyRpId",deployment.passkeyRpId()).put("passkeysEnabled",deployment.passkeysEnabled());
            if(deployment.publicOrigin().equals("NONE"))value.put("emailRecoveryEnabled",false);
        }
        execute(c,"UPDATE tk_service_settings SET version=?,active_version=?,saved=?,effective=? WHERE id=1",settings.version()+1,settings.active()+1,saved.toString(),effective.toString());
        execute(c,"UPDATE tk_recovery_control SET origin=?,epoch=? WHERE id=1",deployment.publicOrigin().equals("NONE")?null:deployment.publicOrigin(),epoch);
        execute(c,"UPDATE tk_auth_challenge SET consumed=TRUE");execute(c,"UPDATE tk_recovery_link SET consumed=TRUE");execute(c,"UPDATE tk_passkey_ceremony SET consumed=TRUE");
        execute(c,"UPDATE tk_auth_session SET origin_epoch=0");
        // Preserve session rows as recovery evidence; epoch zero cannot match the new positive epoch.
        // Passwords, API tokens, passkey credentials, handles and RP associations remain byte-for-byte intact.
    }
}
