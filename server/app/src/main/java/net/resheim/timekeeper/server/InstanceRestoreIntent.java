package net.resheim.timekeeper.server;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.InstanceArtifacts.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable private handoff. Files alone never authorize database replacement. */
final class InstanceRestoreIntent {
    private static final String FORMAT="timekeeper-instance-restore-intent-v1";
    private static final UUID CONTROL=new UUID(0,0);
    private static final ObjectReader JSON=MAPPER.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path root;private final long maximumBytes;
    record Spec(UUID generation,UUID operation,long reviewVersion,String actor,UUID artifactRoot,UUID previousGeneration,InstanceRestoreDatabase.Deployment deployment) {
        Spec {
            if(generation==null||generation.equals(CONTROL)||generation.equals(previousGeneration)||operation==null||reviewVersion<1||reviewVersion>=9007199254740989L||actor==null||actor.isBlank()||actor.length()>400||artifactRoot==null||deployment==null)throw ApiFailure.validation();
        }
        @Override public String toString(){return "Private instance restore intent";}
    }
    InstanceRestoreIntent(Path root,long maximumBytes) throws IOException {
        if(root==null||maximumBytes<1)throw invalid();this.root=root.toAbsolutePath().normalize();this.maximumBytes=maximumBytes;
        if(!Files.exists(this.root,NOFOLLOW_LINKS))try{Files.createDirectory(this.root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}catch(FileAlreadyExistsException race){/* Another process initialized it. */}
        directory(this.root);sync(this.root.getParent());
    }
    private final class Held implements AutoCloseable {
        final FileChannel channel;final FileLock lock;
        Held() throws IOException {
            directory(root);Path path=root.resolve(".lock");
            if(!Files.exists(path,NOFOLLOW_LINKS))try(var created=createFile(path)){created.force(true);}catch(FileAlreadyExistsException race){/* Another process initialized it. */}
            file(path);channel=FileChannel.open(path,StandardOpenOption.WRITE,NOFOLLOW_LINKS);
            try{lock=channel.tryLock();if(lock==null)throw new IOException("INSTANCE_RESTORE_BUSY");}
            catch(IOException|RuntimeException failure){channel.close();throw new IOException("INSTANCE_RESTORE_BUSY");}
        }
        public void close() throws IOException {try{lock.close();}finally{channel.close();}}
    }
    final class Prepared implements AutoCloseable {
        private final Held held;private final Spec spec;private final String digest;
        private final InstanceBackupArchive.Verified archive;private final InstanceConfiguration.Snapshot target;private boolean closed;
        Prepared(Held held,Spec spec,String digest,InstanceBackupArchive.Verified archive,InstanceConfiguration.Snapshot target){this.held=held;this.spec=spec;this.digest=digest;this.archive=archive;this.target=target;}
        Spec spec(){return spec;}
        Path storage(){if(closed)throw new IllegalStateException("Restore intent is closed");return root;}
        String digest(){return digest;}
        InstanceBackupArchive.Verified archive(){return archive;}
        InstanceConfiguration.Snapshot target(){return target;}
        @Override public String toString(){return "Private prepared instance restore";}
        /** Caller owns its serializable account transaction and commits authorization. */
        long authorize(Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,Instant now) throws SQLException {
            if(closed)throw new SQLException("Restore intent is closed");return authorizeIntent(spec,digest,target.document(),c,administrators,proof,now);
        }
        /** Offline recovery must prove the database committed this exact private intent. */
        String phase(Connection c) throws SQLException {
            if(closed)throw new SQLException("Restore intent is closed");var control=control(c,false);var operation=operation(c,false);var payload=operation.path("payload");
            if(!control.path("restoreIntent").equals(binding())||!payload.path("restoreIntent").equals(binding())||!operation.path("actor").asText().equals(spec.actor())||!payload.path("type").asText().equals("RESTORE")||!Set.of("REVIEW","RESTORED").contains(payload.path("state").asText()))throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");
            String phase=payload.path("restorePhase").asText();long version=operation.path("version").asLong();
            if(payload.path("state").asText().equals("REVIEW")&&phase.equals("PREPARED")&&version==spec.reviewVersion()+1&&control.path("restorePending").asBoolean()&&Objects.equals(control.hasNonNull("restoreGeneration")?control.path("restoreGeneration").asText():null,spec.previousGeneration()==null?null:spec.previousGeneration().toString()))return phase;
            if(payload.path("state").asText().equals("REVIEW")&&phase.equals("DATABASE_APPLIED")&&version==spec.reviewVersion()+2&&control.path("restorePending").asBoolean()&&control.path("restoreGeneration").asText().equals(spec.generation().toString())&&control.path("restore").path("operationId").asText().equals(spec.operation().toString()))return phase;
            if(phase.equals("COMPLETE")&&payload.path("state").asText().equals("RESTORED")&&version==spec.reviewVersion()+3&&!control.path("restorePending").asBoolean()&&control.path("restoreGeneration").asText().equals(spec.generation().toString())&&control.path("restore").path("phase").asText().equals("COMPLETE")&&control.path("restore").path("operationId").asText().equals(spec.operation().toString()))return phase;
            throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");
        }
        /** Files must already be durably published; the caller commits final activation. */
        void complete(Connection c,InstanceRestoreFence.Exclusive exclusive,Instant now) throws SQLException,IOException {
            exclusive.require(c);if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE)throw new SQLException("Complete restore in a serializable transaction");
            var material=InstanceRestoreMaterial.active(root);if(!material.generation().equals(spec.generation())||!material.operation().equals(spec.operation())||!material.intentDigest().equals(digest))throw new IOException("INVALID_INSTANCE_RESTORE_MATERIAL");
            String current=phase(c);var control=control(c,true);
            if(current.equals("COMPLETE")){if(!control.path("restore").path("materialSha256").asText().equals(material.digest()))throw new IOException("INVALID_INSTANCE_RESTORE_MATERIAL");return;}
            if(!current.equals("DATABASE_APPLIED"))throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_APPLIED");
            var operation=operation(c,true);var payload=(ObjectNode)operation.get("payload");payload.put("state","RESTORED").put("restorePhase","COMPLETE");
            control.put("restorePending",false);control.withObject("restore").put("phase","COMPLETE").put("materialSha256",material.digest());
            execute(c,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",control.toString(),CONTROL);
            execute(c,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),payload.toString(),spec.operation());
            execute(c,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),spec.operation(),spec.actor(),now.toString(),object().put("type","RESTORE").put("state","RESTORED").put("action","RESTORE_COMPLETED").toString());
        }
        void targetBinding(Connection c) throws SQLException {InstanceRestoreIntent.targetBinding(c,target.document());}
        private ObjectNode binding(){return object().put("generation",spec.generation().toString()).put("sha256",digest);}
        private ObjectNode operation(Connection c,boolean locked) throws SQLException {
            try(var s=query(c,"SELECT version,actor_owner,payload FROM tk_instance WHERE id=? AND kind='OPERATION'"+(locked?" FOR UPDATE":""),spec.operation());var r=s.executeQuery()) {
                if(!r.next())throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");var result=object().put("version",r.getLong(1)).put("actor",r.getString(2));result.set("payload",parse(r.getString(3)));return result;
            }
        }
        public void close() throws IOException {if(closed)return;closed=true;try{try{archive.close();}finally{target.close();}}finally{held.close();}}
    }
    private static ObjectNode binding(Spec spec,String digest){return object().put("generation",spec.generation().toString()).put("sha256",digest);}
    private static ObjectNode operation(Connection c,UUID id,boolean locked) throws SQLException {
        try(var statement=query(c,"SELECT version,actor_owner,payload FROM tk_instance WHERE id=? AND kind='OPERATION'"+(locked?" FOR UPDATE":""),id);var rows=statement.executeQuery()) {
            if(!rows.next())throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");var result=object().put("version",rows.getLong(1)).put("actor",rows.getString(2));result.set("payload",parse(rows.getString(3)));return result;
        }
    }
    private static void targetBinding(Connection c,JsonNode targetDocument) throws SQLException {
            var properties=targetDocument.path("properties");String configured=properties.path("quarkus.datasource.jdbc.url").asText(),actual=c.getMetaData().getURL();
            try{DatabaseConfiguration.validate(configured);}catch(IllegalArgumentException invalid){throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");}
            boolean postgres=c.getMetaData().getDatabaseProductName().equals("PostgreSQL");
            boolean url=postgres?configured.equals(actual):h2Location(configured).equals(h2Location(actual));
            String user=properties.path("quarkus.datasource.username").asText(),connected=c.getMetaData().getUserName();
            if(!url||!properties.path("quarkus.datasource.db-kind").asText().equals(postgres?"postgresql":"h2")||!(postgres?user.equals(connected):user.equalsIgnoreCase(connected)))throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");
    }
    private static long authorizeIntent(Spec spec,String digest,JsonNode targetDocument,Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,Instant now) throws SQLException {
            if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE)throw new SQLException("Restore intent authorization requires a serializable transaction");
            if(proof==null||proof.verifiedAt().isBefore(now.minusSeconds(300))||proof.verifiedAt().isAfter(now.plusSeconds(5)))throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");
            lock(c);administrators.check(c,proof.principal());targetBinding(c,targetDocument);if(!spec.actor().equals(proof.principal().owner()))throw new ApiFailure(403,"FORBIDDEN");
            var control=control(c,true);String previous=control.hasNonNull("restoreGeneration")?control.path("restoreGeneration").asText():null;
            if(control.path("restorePending").asBoolean()||!Objects.equals(previous,spec.previousGeneration()==null?null:spec.previousGeneration().toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(!control.path("artifactRootId").asText().equals(spec.artifactRoot().toString()))throw new ApiFailure(503,"INSTANCE_STORAGE_MISMATCH");
            if(!InstanceRestoreTarget.inspect(c,spec.actor()).databaseEligible())throw new ApiFailure(409,"INSTANCE_TARGET_NOT_EMPTY");
            var operation=operation(c,spec.operation(),true);var payload=(ObjectNode)operation.get("payload");
            if(operation.path("version").asLong()!=spec.reviewVersion())throw new ApiFailure(412,"STALE_VERSION");
            if(!operation.path("actor").asText().equals(spec.actor())||!payload.path("type").asText().equals("RESTORE")||!payload.path("state").asText().equals("REVIEW")||!payload.path("upload").path("session").asText().equals(proof.principal().digest()))throw new ApiFailure(403,"FORBIDDEN");
            if(payload.has("restorePhase")&&(!payload.path("restorePhase").asText().equals("STAGED")||!payload.path("restoreIntent").equals(binding(spec,digest))))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            payload.put("restorePhase","PREPARED");payload.set("restoreIntent",binding(spec,digest));control.set("restoreIntent",binding(spec,digest));control.put("restorePending",true);
            execute(c,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),payload.toString(),spec.operation());
            execute(c,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",control.toString(),CONTROL);
            execute(c,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),spec.operation(),spec.actor(),now.toString(),object().put("type","RESTORE").put("state","REVIEW").put("action","RESTORE_PREPARED").toString());
            return spec.reviewVersion()+1;
    }
    /** Lightweight published staging; no duplicate source/target file arrays. */
    final class Staged implements AutoCloseable {
        private final Held held;private final Spec spec;private final String digest;private boolean closed;
        Staged(Held held,Spec spec,String digest){this.held=held;this.spec=spec;this.digest=digest;}
        Spec spec(){return spec;}String digest(){return digest;}Path storage(){if(closed)throw new IllegalStateException("Staging is closed");return root;}
        long authorize(Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,InstanceConfiguration.Snapshot current,Instant now) throws SQLException,IOException {
            if(closed)throw invalid();var reference=reference();if(!reference.spec().equals(spec)||!reference.digest().equals(digest))throw invalid();
            Path path=reference.stage().resolve("target-configuration.json");var expected=reference.metadata().path("entries").path("target-configuration.json");file(path);
            if(Files.size(path)!=expected.path("bytes").asLong()||!sha256(path).equals(expected.path("sha256").asText()))throw invalid();var target=read(path,4_000_000);
            if(!target.equals(current.document()))throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");
            for(var entry:current.files().entrySet()) {
                var receipt=reference.metadata().path("entries").path("target/"+entry.getKey());
                String actual;try{actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue()));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}
                if(!receipt.path("sha256").asText().equals(actual)||receipt.path("bytes").asLong()!=entry.getValue().length)throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");
            }
            return authorizeIntent(spec,digest,target,c,administrators,proof,now);
        }
        public void close() throws IOException {if(closed)return;closed=true;held.close();}
    }
    Staged staged() throws IOException {Held held=new Held();boolean success=false;try{var value=reference();var result=new Staged(held,value.spec(),value.digest());success=true;return result;}finally{if(!success)held.close();}}
    Prepared prepare(Spec spec,InstanceBackupArchive.Verified archive,InstanceConfiguration.Snapshot target,Instant now) throws IOException {
        var staged=stage(spec,archive,target,now);boolean success=false;
        try{var result=load(staged.held);staged.closed=true;success=true;return result;}finally{if(!success)staged.close();}
    }
    Staged stage(Spec spec,InstanceBackupArchive.Verified archive,InstanceConfiguration.Snapshot target,Instant now) throws IOException {
        Held held=new Held();Path stage=null;boolean complete=false;
        try {
            if(Files.exists(root.resolve("prepared.json"),NOFOLLOW_LINKS))throw new IOException("INSTANCE_RESTORE_BUSY");
            InstanceRestoreDatabase.verifyArchive(archive);InstanceConfiguration.validate(target.document(),target.files().keySet());
            stage=root.resolve(spec.generation().toString());Files.createDirectory(stage,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var metadata=object().put("format",FORMAT).put("generation",spec.generation().toString()).put("operation",spec.operation().toString()).put("reviewVersion",spec.reviewVersion()).put("actor",spec.actor()).put("artifactRoot",spec.artifactRoot().toString()).put("previousGeneration",spec.previousGeneration()==null?null:spec.previousGeneration().toString()).put("createdAt",now.toString());
            metadata.set("deployment",MAPPER.valueToTree(spec.deployment()));var index=metadata.putObject("entries");
            copy(stage,"database.json",archive.database(),index);writeJson(stage,"source-manifest.json",archive.manifest(),index);
            writeJson(stage,"source-configuration.json",archive.configuration().document(),index);writeJson(stage,"target-configuration.json",target.document(),index);
            for(var entry:archive.configuration().files().entrySet())write(stage,"source/"+entry.getKey(),entry.getValue(),index);
            for(var entry:target.files().entrySet())write(stage,"target/"+entry.getKey(),entry.getValue(),index);
            InstanceRestoreDatabase.verifyArchive(archive);byte[] bytes=MAPPER.writeValueAsBytes(metadata);if(bytes.length>131072)throw invalid();
            write(stage,"intent.json",bytes,null);sync(stage);sync(root);String digest=sha256(stage.resolve("intent.json"));
            savePointer(object().put("format",FORMAT).put("generation",spec.generation().toString()).put("sha256",digest));
            var result=new Staged(held,spec,digest);complete=true;return result;
        }catch(IOException|RuntimeException failure){throw failure;}
        finally{if(!complete){try{if(stage!=null&&!Files.exists(root.resolve("prepared.json"),NOFOLLOW_LINKS))InstanceBackupArchive.remove(stage);}finally{held.close();}}}
    }
    Path storage(){return root;}
    /** Complete-state cleanup needs no image, so a crash during deletion is resumable. */
    void cleanupCompleted(Connection c,InstanceRestoreMaterial.Installed expected) throws IOException,SQLException {
        try(var held=new Held()) {
            var control=control(c,false);var restore=control.path("restore");var active=InstanceRestoreMaterial.active(root);
            if(control.path("restorePending").asBoolean()||!restore.path("phase").asText().equals("COMPLETE")||!active.equals(expected)||!control.path("restoreGeneration").asText().equals(active.generation().toString())||!restore.path("materialSha256").asText().equals(active.digest())||!restore.path("operationId").asText().equals(active.operation().toString())||!control.path("restoreIntent").path("generation").asText().equals(active.generation().toString())||!control.path("restoreIntent").path("sha256").asText().equals(active.intentDigest()))throw invalid();
            Path pointer=root.resolve("prepared.json");
            if(Files.exists(pointer,NOFOLLOW_LINKS)) {
                var value=read(pointer,1024);fields(value,Set.of("format","generation","sha256"));
                if(value.path("generation").asText().equals(active.generation().toString())) {
                    if(!value.path("format").asText().equals(FORMAT)||!value.path("sha256").asText().equals(active.intentDigest())||!control.path("restoreIntent").path("sha256").asText().equals(active.intentDigest()))throw invalid();
                    Files.delete(pointer);sync(root);
                }
            }
            Path stage=root.resolve(active.generation().toString());if(Files.exists(stage,NOFOLLOW_LINKS)){directory(stage);InstanceBackupArchive.remove(stage);sync(root);}
        }
    }
    /** Unreferenced private plaintext cannot authorize a restore and can be reclaimed. */
    int cleanupOrphans(Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,UUID operation,Instant now) throws SQLException,IOException {
        try(var held=new Held()) {
            lock(c);administrators.check(c,proof.principal());if(proof.verifiedAt().isBefore(now.minusSeconds(300))||proof.verifiedAt().isAfter(now.plusSeconds(5)))throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");
            var control=control(c,true);if(control.path("restorePending").asBoolean())throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");var protectedIds=new HashSet<UUID>();
            if(control.hasNonNull("restoreGeneration"))protectedIds.add(uuid(control.path("restoreGeneration").asText()));
            if(Files.exists(root.resolve("active.json"),NOFOLLOW_LINKS))protectedIds.add(InstanceRestoreMaterial.active(root).generation());
            Path pointer=root.resolve("prepared.json");
            if(Files.exists(pointer,NOFOLLOW_LINKS)) {
                var reference=reference();boolean obsolete=false;
                try{var receipt=operation(c,reference.spec().operation(),true);obsolete=Set.of("FAILED","INTERRUPTED","EXPIRED").contains(receipt.path("payload").path("state").asText())||receipt.path("payload").path("restorePhase").asText().equals("STAGE_FAILED");}catch(ApiFailure missing){if(missing.status!=409)throw missing;obsolete=true;}
                if(obsolete){if(protectedIds.contains(reference.spec().generation()))throw invalid();InstanceRestoreMaterial.discardUnpublished(root,reference.spec().generation(),reference.digest());Files.delete(pointer);sync(root);}else protectedIds.add(reference.spec().generation());
            }
            int removed=0;Path materials=root.resolve("material");
            if(Files.exists(materials,NOFOLLOW_LINKS)){directory(materials);try(var entries=Files.list(materials)){for(Path path:entries.toList())if(path.getFileName().toString().matches("\\.install-[a-f0-9-]{36}")){directory(path);InstanceBackupArchive.remove(path);removed++;}}sync(materials);}
            try(var entries=Files.list(root)) {
                for(Path path:entries.toList()) {
                    String name=path.getFileName().toString();
                    if(name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&!protectedIds.contains(UUID.fromString(name))){directory(path);InstanceBackupArchive.remove(path);removed++;}
                    else if(name.matches("\\.pointer-[a-f0-9-]{36}")){file(path);Files.delete(path);removed++;}
                }
            }
            if(removed>0){sync(root);execute(c,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),operation,proof.principal().owner(),now.toString(),object().put("type","RESTORE").put("state","EXPIRED").put("action","RESTORE_STAGING_CLEANED").put("count",removed).toString());}return removed;
        }
    }
    Prepared open() throws IOException {Held held=new Held();boolean complete=false;try{var result=load(held);complete=true;return result;}finally{if(!complete)held.close();}}
    /** Cancellation reads only the small handoff header, never decrypted business data. */
    final class Cancellation implements AutoCloseable {
        private final Held held;private final UUID operation,generation;private final String digest,actor;private final Spec spec;private boolean closed;
        Cancellation(Held held,Connection c,UUID operation) throws IOException,SQLException {
            this.held=held;this.operation=operation;var row=cancellationOperation(c,false);var payload=row.path("payload");actor=row.path("actor").asText();
            if(payload.path("state").asText().equals("EXPIRED")&&payload.path("restorePhase").asText().equals("CANCELLED")||payload.path("state").asText().equals("REVIEW")&&Set.of("PREPARED","STAGED").contains(payload.path("restorePhase").asText())) {
                var binding=payload.path("restoreIntent");fields(binding,Set.of("generation","sha256"));generation=uuid(binding.path("generation").asText());digest=binding.path("sha256").asText();if(!digest.matches("[a-f0-9]{64}"))throw invalid();spec=null;
            }else {
                var reference=reference();spec=reference.spec();generation=spec.generation();digest=reference.digest();
                if(!operation.equals(spec.operation())||!actor.equals(spec.actor()))throw invalid();
            }
        }
        /** Live cancellation is only for staging whose consent has not committed. */
        void cancelUncommitted(Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,Instant now) throws SQLException,IOException {
            transaction(c);if(proof==null||proof.verifiedAt().isBefore(now.minusSeconds(300))||proof.verifiedAt().isAfter(now.plusSeconds(5)))throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");
            lock(c);administrators.check(c,proof.principal());if(!actor.equals(proof.principal().owner()))throw new ApiFailure(403,"FORBIDDEN");
            var row=cancellationOperation(c,true);
            if(!row.path("payload").path("upload").path("session").asText().equals(proof.principal().digest()))throw new ApiFailure(403,"FORBIDDEN");
            recordCancellation(c,row,now,false);
        }
        /** Stopped-server recovery uses the original administrator's current password. */
        void cancelOffline(Connection c,InstanceRestoreFence.Exclusive exclusive,String username,String password,Instant now) throws SQLException,IOException {
            exclusive.require(c);if(!c.getAutoCommit())throw new SQLException("Offline cancellation requires a fresh transaction");
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);
            try {
                lock(c);var row=cancellationOperation(c,true);checkPhase(c,row,true);
                String key="instance-restore:cancel:"+actor;boolean allowed=attempt(c,key,5,java.time.Duration.ofMinutes(5),now);
                boolean account=false;try(var statement=query(c,"SELECT username,state,administrator FROM tk_account WHERE owner_id=?",actor);var rows=statement.executeQuery()) {
                    if(rows.next())account=Objects.equals(rows.getString(1),username)&&rows.getString(2).equals("ENABLED")&&rows.getBoolean(3);
                }
                boolean passwordValid=password!=null&&password.length()<=1024&&currentPassword(c,actor,password);
                if(!allowed||!account||!passwordValid) {
                    c.commit();c.setAutoCommit(true);throw new ApiFailure(allowed?422:429,allowed?"INSTANCE_PROOF_REQUIRED":"RATE_LIMITED");
                }
                execute(c,"DELETE FROM tk_auth_throttle WHERE id=?",hash(key));recordCancellation(c,row,now,true);c.commit();c.setAutoCommit(true);
            }catch(SQLException|IOException|RuntimeException failure){if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}throw failure;}
        }
        private void recordCancellation(Connection c,ObjectNode row,Instant now,boolean pendingAllowed) throws SQLException,IOException {
            checkPhase(c,row,pendingAllowed);var payload=(ObjectNode)row.path("payload");
            if(payload.path("restorePhase").asText().equals("CANCELLED"))return;
            var control=control(c,true);
            if(control.path("restorePending").asBoolean()) {
                control.put("restorePending",false);
                if(!control.hasNonNull("restoreGeneration"))control.remove("restoreIntent");
                else {
                    var active=InstanceRestoreMaterial.active(root);var previous=control.path("restore");
                    if(!active.generation().toString().equals(control.path("restoreGeneration").asText())||!previous.path("phase").asText().equals("COMPLETE")||!previous.path("operationId").asText().equals(active.operation().toString())||!previous.path("materialSha256").asText().equals(active.digest()))throw invalid();
                    control.set("restoreIntent",object().put("generation",active.generation().toString()).put("sha256",active.intentDigest()));
                }
                execute(c,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",control.toString(),CONTROL);
            }
            payload.put("state","EXPIRED").put("restorePhase","CANCELLED");payload.set("restoreIntent",binding());payload.remove(List.of("download","worker","leaseUntil"));
            execute(c,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),payload.toString(),operation);
            execute(c,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),operation,actor,now.toString(),object().put("type","RESTORE").put("state","EXPIRED").put("action","RESTORE_CANCELLED").toString());
        }
        private void checkPhase(Connection c,ObjectNode row,boolean pendingAllowed) throws SQLException {
            if(closed)throw new SQLException("Restore cancellation is closed");var payload=row.path("payload");var control=control(c,true);
            if(!row.path("actor").asText().equals(actor)||!payload.path("type").asText().equals("RESTORE")||control.path("restoreGeneration").asText().equals(generation.toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(payload.path("state").asText().equals("EXPIRED")&&payload.path("restorePhase").asText().equals("CANCELLED")&&payload.path("restoreIntent").equals(binding())&&!control.path("restorePending").asBoolean())return;
            // Committed consent is authoritative even if its staged header or
            // decrypted image was damaged. Cancellation never applies that image.
            if(pendingAllowed&&payload.path("state").asText().equals("REVIEW")&&control.path("restorePending").asBoolean()&&payload.path("restorePhase").asText().equals("PREPARED")&&payload.path("restoreIntent").equals(binding())&&control.path("restoreIntent").equals(binding()))return;
            if(!control.path("restorePending").asBoolean()&&payload.path("state").asText().equals("REVIEW")&&payload.path("restorePhase").asText().equals("STAGED")&&payload.path("restoreIntent").equals(binding()))return;
            if(spec==null||!control.path("artifactRootId").asText().equals(spec.artifactRoot().toString())||!Objects.equals(control.hasNonNull("restoreGeneration")?control.path("restoreGeneration").asText():null,spec.previousGeneration()==null?null:spec.previousGeneration().toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(!control.path("restorePending").asBoolean()) {
                if(payload.path("state").asText().equals("REVIEW")&&!payload.has("restorePhase")&&row.path("version").asLong()==spec.reviewVersion())return;
                if(Set.of("REVIEW","INTERRUPTED","FAILED").contains(payload.path("state").asText())&&Set.of("STAGING","STAGE_FAILED").contains(payload.path("restorePhase").asText())&&payload.path("restoreStageGeneration").asText().equals(generation.toString())&&row.path("version").asLong()>=spec.reviewVersion()-1&&row.path("version").asLong()<=spec.reviewVersion())return;
            }
            throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
        }
        /** Call only after committing cancellation. Partial deletion can be retried from the receipt. */
        void cleanup(Connection c) throws SQLException,IOException {
            if(closed||!c.getAutoCommit())throw new SQLException("Commit cancellation before cleanup");var row=cancellationOperation(c,false);var control=control(c,false);
            if(!row.path("payload").path("state").asText().equals("EXPIRED")||!row.path("payload").path("restorePhase").asText().equals("CANCELLED")||!row.path("payload").path("restoreIntent").equals(binding())||control.path("restorePending").asBoolean()||control.path("restoreGeneration").asText().equals(generation.toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(Files.exists(root.resolve("active.json"),NOFOLLOW_LINKS)&&InstanceRestoreMaterial.active(root).generation().equals(generation))throw invalid();
            Path pointer=root.resolve("prepared.json");
            if(Files.exists(pointer,NOFOLLOW_LINKS)) {
                var value=read(pointer,1024);fields(value,Set.of("format","generation","sha256"));
                if(!value.path("format").asText().equals(FORMAT)||!value.path("generation").asText().equals(generation.toString())||!value.path("sha256").asText().equals(digest))throw invalid();
                Files.delete(pointer);sync(root);
            }
            if(Files.exists(root.resolve("material"),NOFOLLOW_LINKS))directory(root.resolve("material"));
            for(Path path:List.of(root.resolve(generation.toString()),root.resolve("material").resolve(generation.toString())))if(Files.exists(path,NOFOLLOW_LINKS)){directory(path);InstanceBackupArchive.remove(path);sync(path.getParent());}
        }
        private ObjectNode binding(){return object().put("generation",generation.toString()).put("sha256",digest);}
        private ObjectNode cancellationOperation(Connection c,boolean locked) throws SQLException {
            try(var statement=query(c,"SELECT version,actor_owner,payload FROM tk_instance WHERE id=? AND kind='OPERATION'"+(locked?" FOR UPDATE":""),operation);var rows=statement.executeQuery()) {
                if(!rows.next())throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");var value=object().put("version",rows.getLong(1)).put("actor",rows.getString(2));value.set("payload",parse(rows.getString(3)));return value;
            }
        }
        private void transaction(Connection c) throws SQLException {if(closed||c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE)throw new SQLException("Cancel staging in a serializable transaction");}
        public void close() throws IOException {if(closed)return;closed=true;held.close();}
    }
    Cancellation cancellation(Connection c,UUID operation) throws IOException,SQLException {
        Held held=new Held();boolean complete=false;try{var result=new Cancellation(held,c,operation);complete=true;return result;}finally{if(!complete)held.close();}
    }
    /** Hold the root lock across browser discard's database commit and cleanup. */
    Cancellation cancellationIfStaged(Connection c,UUID operation) throws IOException,SQLException {
        Held held=new Held();boolean complete=false;
        try {
            boolean cancelled=false;try(var statement=query(c,"SELECT payload FROM tk_instance WHERE id=? AND kind='OPERATION'",operation);var rows=statement.executeQuery()) {
                if(rows.next()){var payload=parse(rows.getString(1));cancelled=payload.path("type").asText().equals("RESTORE")&&payload.path("state").asText().equals("EXPIRED")&&payload.path("restorePhase").asText().equals("CANCELLED")||payload.path("type").asText().equals("RESTORE")&&payload.path("state").asText().equals("REVIEW")&&payload.path("restorePhase").asText().equals("STAGED");}
            }
            if(!cancelled&&(!Files.exists(root.resolve("prepared.json"),NOFOLLOW_LINKS)||!reference().spec().operation().equals(operation)))return null;
            var result=new Cancellation(held,c,operation);complete=true;return result;
        }finally{if(!complete)held.close();}
    }
    record Reference(Spec spec,String digest,Path stage,ObjectNode metadata) { @Override public String toString(){return "Private restore handoff header";} }
    private Reference reference() throws IOException {
        try {
            var pointer=read(root.resolve("prepared.json"),1024);fields(pointer,Set.of("format","generation","sha256"));if(!pointer.path("format").asText().equals(FORMAT)||!pointer.path("generation").isTextual()||!pointer.path("sha256").isTextual()||!pointer.path("sha256").asText().matches("[a-f0-9]{64}"))throw invalid();
            UUID generation=uuid(pointer.path("generation").asText());Path stage=root.resolve(generation.toString());directory(stage);
            file(stage.resolve("intent.json"));String digest=sha256(stage.resolve("intent.json"));if(!digest.equals(pointer.path("sha256").asText()))throw invalid();
            var metadata=read(stage.resolve("intent.json"),131072);fields(metadata,Set.of("format","generation","operation","reviewVersion","actor","artifactRoot","previousGeneration","createdAt","deployment","entries"));
            if(!metadata.path("format").asText().equals(FORMAT)||!metadata.path("generation").asText().equals(generation.toString())||!metadata.path("reviewVersion").isIntegralNumber()||!metadata.path("reviewVersion").canConvertToLong()||!metadata.path("entries").isObject())throw invalid();
            for(String name:List.of("generation","operation","actor","artifactRoot","createdAt"))if(!metadata.path(name).isTextual())throw invalid();
            if(!metadata.path("previousGeneration").isNull()&&!metadata.path("previousGeneration").isTextual())throw invalid();
            Instant.parse(metadata.path("createdAt").asText());var deployment=metadata.path("deployment");fields(deployment,Set.of("packageId","publicOrigin","passkeyRpId","passkeysEnabled"));if(!deployment.path("passkeysEnabled").isBoolean())throw invalid();for(String name:List.of("packageId","publicOrigin","passkeyRpId"))if(!deployment.path(name).isTextual())throw invalid();
            var spec=new Spec(generation,uuid(metadata.path("operation").asText()),metadata.path("reviewVersion").asLong(),metadata.path("actor").asText(),uuid(metadata.path("artifactRoot").asText()),metadata.path("previousGeneration").isNull()?null:uuid(metadata.path("previousGeneration").asText()),new InstanceRestoreDatabase.Deployment(deployment.path("packageId").asText(),deployment.path("publicOrigin").asText(),deployment.path("passkeyRpId").asText(),deployment.path("passkeysEnabled").asBoolean()));
            return new Reference(spec,digest,stage,metadata);
        }catch(IOException failure){throw failure;}catch(RuntimeException failure){throw invalid();}
    }
    private Prepared load(Held held) throws IOException {
        InstanceConfiguration.Snapshot source=null,target=null;Path scratch=null;
        try {
            var reference=reference();var spec=reference.spec();String digest=reference.digest();Path stage=reference.stage();var metadata=reference.metadata();
            long total=0;var entries=metadata.path("entries");
            for(var iterator=entries.fields();iterator.hasNext();) {
                var entry=iterator.next();if(!valid(entry.getKey()))throw invalid();var receipt=entry.getValue();fields(receipt,Set.of("bytes","sha256"));Path path=stage.resolve(entry.getKey());parents(stage,path.getParent(),false);file(path);
                if(!receipt.path("sha256").isTextual()||!receipt.path("sha256").asText().matches("[a-f0-9]{64}")||!receipt.path("bytes").isIntegralNumber()||!receipt.path("bytes").canConvertToLong()||receipt.path("bytes").asLong()<0||Files.size(path)!=receipt.path("bytes").asLong()||!sha256(path).equals(receipt.path("sha256").asText()))throw invalid();
                total=Math.addExact(total,Files.size(path));if(total>maximumBytes)throw invalid();
            }
            for(String name:List.of("database.json","source-manifest.json","source-configuration.json","target-configuration.json"))if(!entries.has(name))throw invalid();
            var manifest=read(stage.resolve("source-manifest.json"),1_000_000);source=configuration(stage,"source",entries);target=configuration(stage,"target",entries);
            var expected=new HashSet<String>(Set.of("database.json","source-manifest.json","source-configuration.json","target-configuration.json"));for(String name:source.files().keySet())expected.add("source/"+name);for(String name:target.files().keySet())expected.add("target/"+name);fields(entries,expected);
            scratch=Files.createTempDirectory(stage,".work-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var verified=new InstanceBackupArchive.Verified(scratch,stage.resolve("database.json"),manifest,source,InstanceDatabaseImage.validate(stage.resolve("database.json")));InstanceRestoreDatabase.verifyArchive(verified);
            return new Prepared(held,spec,digest,verified,target);
        }catch(Exception failure){if(source!=null)source.close();if(target!=null)target.close();if(scratch!=null)InstanceBackupArchive.remove(scratch);throw invalid();}
    }
    private InstanceConfiguration.Snapshot configuration(Path stage,String prefix,JsonNode entries) throws IOException {
        var document=read(stage.resolve(prefix+"-configuration.json"),4_000_000);var files=new LinkedHashMap<String,byte[]>();
        try {
            long total=0;
            for(var iterator=entries.fieldNames();iterator.hasNext();) {String name=iterator.next();if(name.startsWith(prefix+"/")){Path path=stage.resolve(name);if(Files.size(path)>InstanceConfiguration.MAX_FILE_BYTES||(total+=Files.size(path))>InstanceConfiguration.MAX_TOTAL_BYTES)throw invalid();files.put(name.substring(prefix.length()+1),Files.readAllBytes(path));}}
            InstanceConfiguration.validate(document,files.keySet());return new InstanceConfiguration.Snapshot(document,files);
        }catch(IOException|RuntimeException failure){files.values().forEach(bytes->Arrays.fill(bytes,(byte)0));throw invalid();}
    }
    private void copy(Path stage,String name,Path source,ObjectNode index) throws IOException {
        file(source);budget(index,Files.size(source));Path path=stage.resolve(name);parents(stage,path.getParent(),true);
        try(var input=FileChannel.open(source,StandardOpenOption.READ,NOFOLLOW_LINKS);var output=createFile(path)) {long size=input.size();if(size>maximumBytes)throw invalid();long offset=0;while(offset<size){long count=input.transferTo(offset,size-offset,output);if(count<1)throw invalid();offset+=count;}output.force(true);}
        entry(index,name,path);sync(path.getParent());
    }
    private void writeJson(Path stage,String name,JsonNode value,ObjectNode index) throws IOException {byte[] bytes=MAPPER.writeValueAsBytes(value);try{write(stage,name,bytes,index);}finally{Arrays.fill(bytes,(byte)0);}}
    private void write(Path stage,String name,byte[] bytes,ObjectNode index) throws IOException {
        if(index!=null)budget(index,bytes.length);Path path=stage.resolve(name);parents(stage,path.getParent(),true);try(var output=createFile(path)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}if(index!=null)entry(index,name,path);sync(path.getParent());
    }
    private void budget(ObjectNode index,long bytes) throws IOException {long total=bytes;for(var value:index)total=Math.addExact(total,value.path("bytes").asLong());if(total>maximumBytes)throw invalid();}
    private void entry(ObjectNode index,String name,Path path) throws IOException {long total=Files.size(path);for(var value:index)total=Math.addExact(total,value.path("bytes").asLong());if(total>maximumBytes)throw invalid();index.putObject(name).put("bytes",Files.size(path)).put("sha256",sha256(path));}
    private void savePointer(ObjectNode pointer) throws IOException {
        Path temporary=root.resolve(".pointer-"+UUID.randomUUID());try{write(root,temporary.getFileName().toString(),MAPPER.writeValueAsBytes(pointer),null);Files.move(temporary,root.resolve("prepared.json"),StandardCopyOption.ATOMIC_MOVE);sync(root);}finally{Files.deleteIfExists(temporary);}
    }
    private static ObjectNode read(Path path,int limit) throws IOException {file(path);if(Files.size(path)>limit)throw invalid();try(var input=Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS)){JsonNode value=JSON.readTree(input);if(value==null||!value.isObject())throw invalid();return (ObjectNode)value;}}
    private static void parents(Path root,Path directory,boolean create) throws IOException {if(directory.equals(root))return;parents(root,directory.getParent(),create);if(create&&!Files.exists(directory,NOFOLLOW_LINKS))Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));InstanceArtifacts.directory(directory);sync(directory.getParent());}
    private static String h2Location(String url) {
        if(!url.startsWith("jdbc:h2:"))return "";String location=url.substring(8).split(";",2)[0];if(location.startsWith("file:"))location=location.substring(5);
        if(location.startsWith("mem:")||location.startsWith("tcp:")||location.startsWith("ssl:"))return location;
        if(location.startsWith("~/"))location=System.getProperty("user.home")+location.substring(1);return Path.of(location).toAbsolutePath().normalize().toString();
    }
    private static boolean valid(String name){return Set.of("database.json","source-manifest.json","source-configuration.json","target-configuration.json").contains(name)||name.matches("(?:source|target)/configuration/[0-9]{4}\\.(?:bin|p12|pfx|jks|pem|crt|key|json|properties|der)");}
    private static UUID uuid(String text) throws IOException {try{UUID value=UUID.fromString(text);if(!value.toString().equals(text))throw invalid();return value;}catch(RuntimeException failure){throw invalid();}}
    private static void fields(JsonNode value,Set<String> expected) throws IOException {var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);if(!value.isObject()||!fields.equals(expected))throw invalid();}
    private static ObjectNode control(Connection c,boolean locked) throws SQLException {try(var s=query(c,"SELECT payload FROM tk_instance WHERE id=?"+(locked?" FOR UPDATE":""),CONTROL);var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing instance control");return (ObjectNode)parse(r.getString(1));}}
    private static IOException invalid(){return new IOException("INVALID_INSTANCE_RESTORE_INTENT");}
}
