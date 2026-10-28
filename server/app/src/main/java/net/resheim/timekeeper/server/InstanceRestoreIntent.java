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
            if(generation==null||generation.equals(CONTROL)||operation==null||reviewVersion<1||reviewVersion>=9007199254740989L||actor==null||actor.isBlank()||actor.length()>400||artifactRoot==null||deployment==null)throw ApiFailure.validation();
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
        InstanceBackupArchive.Verified archive(){return archive;}
        InstanceConfiguration.Snapshot target(){return target;}
        @Override public String toString(){return "Private prepared instance restore";}
        /** Caller owns its serializable account transaction and commits authorization. */
        long authorize(Connection c,InstanceAdministrator administrators,InstanceAdministrator.Proof proof,Instant now) throws SQLException {
            if(closed||c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE)throw new SQLException("Restore intent authorization requires a serializable transaction");
            if(proof==null||proof.verifiedAt().isBefore(now.minusSeconds(300))||proof.verifiedAt().isAfter(now.plusSeconds(5)))throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");
            lock(c);administrators.check(c,proof.principal());targetBinding(c);if(!spec.actor().equals(proof.principal().owner()))throw new ApiFailure(403,"FORBIDDEN");
            var control=control(c,true);String previous=control.hasNonNull("restoreGeneration")?control.path("restoreGeneration").asText():null;
            if(control.path("restorePending").asBoolean()||!Objects.equals(previous,spec.previousGeneration()==null?null:spec.previousGeneration().toString()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            if(!control.path("artifactRootId").asText().equals(spec.artifactRoot().toString()))throw new ApiFailure(503,"INSTANCE_STORAGE_MISMATCH");
            if(!InstanceRestoreTarget.inspect(c,spec.actor()).databaseEligible())throw new ApiFailure(409,"INSTANCE_TARGET_NOT_EMPTY");
            var operation=operation(c,true);var payload=(ObjectNode)operation.get("payload");
            if(operation.path("version").asLong()!=spec.reviewVersion())throw new ApiFailure(412,"STALE_VERSION");
            if(!operation.path("actor").asText().equals(spec.actor())||!payload.path("type").asText().equals("RESTORE")||!payload.path("state").asText().equals("REVIEW")||!payload.path("upload").path("session").asText().equals(proof.principal().digest()))throw new ApiFailure(403,"FORBIDDEN");
            payload.put("restorePhase","PREPARED");payload.set("restoreIntent",binding());control.set("restoreIntent",binding());control.put("restorePending",true);
            execute(c,"UPDATE tk_instance SET version=version+1,occurred_at=?,payload=? WHERE id=?",now.toString(),payload.toString(),spec.operation());
            execute(c,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",control.toString(),CONTROL);
            execute(c,"INSERT INTO tk_instance(id,kind,version,operation_id,actor_owner,occurred_at,payload) VALUES(?,'AUDIT',1,?,?,?,?)",UUID.randomUUID(),spec.operation(),spec.actor(),now.toString(),object().put("type","RESTORE").put("state","REVIEW").put("action","RESTORE_PREPARED").toString());
            return spec.reviewVersion()+1;
        }
        /** Offline recovery must prove the database committed this exact private intent. */
        String phase(Connection c) throws SQLException {
            if(closed)throw new SQLException("Restore intent is closed");var control=control(c,false);var operation=operation(c,false);var payload=operation.path("payload");
            if(!control.path("restoreIntent").equals(binding())||!payload.path("restoreIntent").equals(binding())||!operation.path("actor").asText().equals(spec.actor())||!payload.path("type").asText().equals("RESTORE")||!payload.path("state").asText().equals("REVIEW"))throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");
            String phase=payload.path("restorePhase").asText();long version=operation.path("version").asLong();
            if(phase.equals("PREPARED")&&version==spec.reviewVersion()+1&&control.path("restorePending").asBoolean()&&Objects.equals(control.hasNonNull("restoreGeneration")?control.path("restoreGeneration").asText():null,spec.previousGeneration()==null?null:spec.previousGeneration().toString()))return phase;
            if(phase.equals("DATABASE_APPLIED")&&version==spec.reviewVersion()+2&&control.path("restorePending").asBoolean()&&control.path("restoreGeneration").asText().equals(spec.generation().toString())&&control.path("restore").path("operationId").asText().equals(spec.operation().toString()))return phase;
            throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");
        }
        private void targetBinding(Connection c) throws SQLException {
            var properties=target.document().path("properties");String configured=properties.path("quarkus.datasource.jdbc.url").asText(),actual=c.getMetaData().getURL();
            try{DatabaseConfiguration.validate(configured);}catch(IllegalArgumentException invalid){throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");}
            boolean postgres=c.getMetaData().getDatabaseProductName().equals("PostgreSQL");
            boolean url=postgres?configured.equals(actual):h2Location(configured).equals(h2Location(actual));
            String user=properties.path("quarkus.datasource.username").asText(),connected=c.getMetaData().getUserName();
            if(!url||!properties.path("quarkus.datasource.db-kind").asText().equals(postgres?"postgresql":"h2")||!(postgres?user.equals(connected):user.equalsIgnoreCase(connected)))throw new ApiFailure(409,"INSTANCE_TARGET_CONFIGURATION_CHANGED");
        }
        private ObjectNode binding(){return object().put("generation",spec.generation().toString()).put("sha256",digest);}
        private ObjectNode operation(Connection c,boolean locked) throws SQLException {
            try(var s=query(c,"SELECT version,actor_owner,payload FROM tk_instance WHERE id=? AND kind='OPERATION'"+(locked?" FOR UPDATE":""),spec.operation());var r=s.executeQuery()) {
                if(!r.next())throw new ApiFailure(409,"INSTANCE_RESTORE_NOT_AUTHORIZED");var result=object().put("version",r.getLong(1)).put("actor",r.getString(2));result.set("payload",parse(r.getString(3)));return result;
            }
        }
        public void close() throws IOException {if(closed)return;closed=true;try{try{archive.close();}finally{target.close();}}finally{held.close();}}
    }
    Prepared prepare(Spec spec,InstanceBackupArchive.Verified archive,InstanceConfiguration.Snapshot target,Instant now) throws IOException {
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
            var result=load(held);complete=true;return result;
        }catch(IOException|RuntimeException failure){throw failure;}
        finally{if(!complete){try{if(stage!=null&&!Files.exists(root.resolve("prepared.json"),NOFOLLOW_LINKS))InstanceBackupArchive.remove(stage);}finally{held.close();}}}
    }
    Prepared open() throws IOException {Held held=new Held();boolean complete=false;try{var result=load(held);complete=true;return result;}finally{if(!complete)held.close();}}
    private Prepared load(Held held) throws IOException {
        InstanceConfiguration.Snapshot source=null,target=null;Path scratch=null;
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
