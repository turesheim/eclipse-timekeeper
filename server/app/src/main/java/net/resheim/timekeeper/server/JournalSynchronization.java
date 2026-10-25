package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Narrow gateway authority and completed-transfer handoff, separate from ordinary API tokens. */
@ApplicationScoped
class JournalSynchronization {
    @Inject ServerStore store;
    @Inject AccountStore accounts;
    @Inject JournalConfiguration configuration;
    private ScheduledExecutorService worker;
    record Gateway(JournalConfiguration.Deployment deployment,JournalConfiguration.Binding account) {
        @Override public String toString(){return "Journal gateway authority";}
    }
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY=PosixFilePermissions.fromString("rwx------"),FILE=PosixFilePermissions.fromString("rw-------");
    void start(){
        if(worker!=null)return;
        worker=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"Timekeeper journal imports");thread.setDaemon(true);return thread;});
        worker.scheduleWithFixedDelay(this::poll,2,2,TimeUnit.SECONDS);
    }
    @jakarta.annotation.PreDestroy void stop(){if(worker!=null){worker.shutdownNow();worker=null;}}
    private void poll(){
        try{
            var deployment=configuration.load();
            for(var account:deployment.accounts()){
                if(Thread.currentThread().isInterrupted())return;
                try{rescan(account.owner());}catch(RuntimeException unavailable){/* Metadata status is exposed by the account route; never log journal input. */}
            }
        }catch(JournalConfiguration.Unavailable missing){/* Optional deployment is not provisioned. */}
    }
    private void existingAccount(String owner){
        accounts.tx(connection->{var account=AccountStore.account(connection,owner,false);if(!account.path("state").asText().equals("ENABLED"))throw new ApiFailure(403,"JOURNAL_ACCOUNT_DISABLED");return null;});
    }
    <T>T gateway(String username,String authorization,Function<Gateway,T> operation){
        final JournalConfiguration.Deployment deployment;
        try{deployment=configuration.load();}catch(JournalConfiguration.Unavailable unavailable){throw new ApiFailure(503,unavailable.code);}
        var binding=deployment.username(username).orElseThrow(()->ApiFailure.unauthorized(false));
        if(authorization==null||!authorization.matches("JournalTransport [A-Za-z0-9_-]{43,128}"))throw ApiFailure.unauthorized(false);
        byte[] supplied=HexFormat.of().parseHex(hash(authorization.substring("JournalTransport ".length())));
        if(!MessageDigest.isEqual(supplied,HexFormat.of().parseHex(binding.brokerHash())))throw ApiFailure.unauthorized(false);
        // Never let ServerStore's legacy owner initialization provision an owner from an unknown binding.
        existingAccount(binding.owner());
        return store.transaction(binding.owner(),()->{
            var account=AccountStore.account(store.current().connection(),store.owner(),true);
            if(!account.path("state").asText().equals("ENABLED"))throw new ApiFailure(403,"JOURNAL_ACCOUNT_DISABLED");
            return operation.apply(new Gateway(deployment,binding));
        });
    }
    ObjectNode key(Gateway gateway,String publicKey){
        var canonical=JournalKey.parse(publicKey);
        var state=new JournalState(store);
        if(!new JournalDevices(store).settings().path("enabled").asBoolean())throw new ApiFailure(403,"JOURNAL_DISABLED");
        for(var device:state.list(JOURNAL_DEVICE))if(!device.path("revoked").asBoolean()&&device.path("publicKey").asText().equals(canonical.publicKey()))
            return object().put("deviceId",device.path("id").asText()).put("publicKey",canonical.publicKey());
        throw ApiFailure.unauthorized(false);
    }
    ObjectNode verify(Gateway gateway,UUID device){var value=new JournalDevices(store).authorize(device);new JournalState(store).update(JOURNAL_DEVICE,value.put("lastVerifiedAt",store.now().toString()));return object().put("status","READY").put("direction","CLIENT_TO_SERVER");}
    ObjectNode begin(Gateway gateway,UUID device){
        var devices=new JournalDevices(store);var credential=devices.authorize(device);var state=new JournalState(store);
        new JournalRetention(store).cleanup(gateway.deployment());
        long active=state.list(JOURNAL_LEASE).stream().filter(lease->lease.path("state").asText().equals("RECEIVING")&&Instant.parse(lease.path("expiresAt").asText()).isAfter(store.now())).count();
        if(active>=2)throw new ApiFailure(429,"JOURNAL_TRANSFER_LIMIT");
        long sequence=credential.path("nextSequence").asLong();if(sequence<1||sequence>=9007199254740990L)throw ApiFailure.conflict();
        state.update(JOURNAL_DEVICE,credential.put("nextSequence",sequence+1));UUID id=UUID.randomUUID();
        var account=AccountStore.account(store.current().connection(),store.owner(),false);
        var lease=state.insert(JOURNAL_LEASE,id,object().put("deviceId",device.toString()).put("sequence",sequence).put("state","RECEIVING")
                .put("syncEpoch",devices.settings().path("epoch").asLong()).put("configurationEpoch",gateway.deployment().epoch()).put("accountVersion",account.path("version").asLong())
                .put("createdAt",store.now().toString()).put("hardEnd",store.now().plusSeconds(900).toString()).put("expiresAt",store.now().plusSeconds(120).toString()));
        folder(gateway.deployment(),id,true);return leaseReply(lease);
    }
    private ObjectNode lease(Gateway gateway,UUID id,boolean completed){
        var value=new JournalState(store).required(JOURNAL_LEASE,id);
        if(!(value.path("state").asText().equals("RECEIVING")||(completed&&value.path("state").asText().equals("COMPLETED"))))throw new ApiFailure(409,"JOURNAL_TRANSFER_ABORTED");
        valid(gateway.deployment(),value);
        if(value.path("state").asText().equals("RECEIVING")&&!Instant.parse(value.path("expiresAt").asText()).isAfter(store.now()))throw new ApiFailure(409,"JOURNAL_TRANSFER_EXPIRED");
        return value;
    }
    private void valid(JournalConfiguration.Deployment deployment,ObjectNode value){
        var devices=new JournalDevices(store);devices.authorize(uuid(value.get("deviceId")));
        var account=AccountStore.account(store.current().connection(),store.owner(),false);
        if(value.path("syncEpoch").asLong()!=devices.settings().path("epoch").asLong()||!value.path("configurationEpoch").asText().equals(deployment.epoch())||value.path("accountVersion").asLong()!=account.path("version").asLong())throw new ApiFailure(409,"JOURNAL_AUTHORIZATION_CHANGED");
    }
    ObjectNode heartbeat(Gateway gateway,UUID id){
        var value=lease(gateway,id,false);Instant hardEnd=Instant.parse(value.path("hardEnd").asText()),next=store.now().plusSeconds(120);
        if(!hardEnd.isAfter(store.now()))throw new ApiFailure(409,"JOURNAL_TRANSFER_EXPIRED");
        value=new JournalState(store).update(JOURNAL_LEASE,value.put("expiresAt",(next.isBefore(hardEnd)?next:hardEnd).toString()));return leaseReply(value);
    }
    ObjectNode abort(Gateway gateway,UUID id){
        var state=new JournalState(store);var value=state.required(JOURNAL_LEASE,id);
        if(value.path("state").asText().equals("RECEIVING")){
            state.update(JOURNAL_LEASE,value.put("state","ABORTED").put("error","JOURNAL_TRANSFER_ABORTED"));
            new JournalRetention(store).cleanup(gateway.deployment());
        }
        return object().put("status",value.path("state").asText());
    }
    ObjectNode upload(Gateway gateway,UUID id,String filename,String encoded){
        lease(gateway,id,false);
        if(filename==null||!filename.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")||encoded==null||encoded.length()>4*((JournalMarkdown.FILE_BYTES+2)/3))throw ApiFailure.validation();
        final byte[] bytes;
        try{bytes=Base64.getDecoder().decode(encoded);if(bytes.length>JournalMarkdown.FILE_BYTES||!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
        Path folder=folder(gateway.deployment(),id,false),target=folder.resolve(filename),temporary=null;
        try{
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
                if(!Arrays.equals(read(target),bytes))throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
                return object().put("status","STAGED");
            }
            try(var files=Files.list(folder)){if(files.count()>=JournalImport.BATCH_FILES)throw new ApiFailure(413,"JOURNAL_BATCH_TOO_LARGE");}
            temporary=Files.createTempFile(folder,"incoming-",".part",PosixFilePermissions.asFileAttribute(FILE));
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);syncDirectory(folder);return object().put("status","STAGED");
        }catch(IOException unsafe){throw new ApiFailure(503,"JOURNAL_PRIVATE_STORAGE_UNAVAILABLE");}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    ObjectNode complete(Gateway gateway,UUID id,JsonNode manifest){
        var lease=lease(gateway,id,true);var state=new JournalState(store);
        var entries=manifest.path("files");if(!manifest.path("format").isIntegralNumber()||manifest.path("format").asLong()!=1||!manifest.path("completed").isBoolean()||!manifest.path("completed").asBoolean()||!entries.isArray()||entries.size()>JournalImport.BATCH_FILES)throw ApiFailure.validation();
        var errors=manifest.has("errors")?manifest.path("errors"):array();
        if(!errors.isArray()||entries.size()+errors.size()>JournalImport.BATCH_FILES)throw ApiFailure.validation();
        var errorNames=new HashSet<String>();
        for(var error:errors){String name=error.path("name").asText();if(!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")||!errorNames.add(name)||!Set.of("FILE_TOO_LARGE","UNSAFE_LOCAL_FILE","FILE_CHANGED","LOCAL_FILE_UNREADABLE").contains(error.path("code").asText()))throw ApiFailure.validation();}
        String manifestHash=hash(canonical(manifest).toString());
        if(lease.path("state").asText().equals("COMPLETED")){
            if(!lease.path("manifestHash").asText().equals(manifestHash))throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
            return object().put("status","COMPLETED").put("files",entries.size());
        }
        var files=new TreeMap<String,byte[]>();Path folder=folder(gateway.deployment(),id,false);
        try{
            for(var entry:entries){
                String name=entry.path("name").asText(),digest=entry.path("sha256").asText();var size=entry.path("bytes");
                if(!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")||!digest.matches("[a-f0-9]{64}")||!size.isIntegralNumber()||size.asLong()<0||size.asLong()>JournalMarkdown.FILE_BYTES||files.containsKey(name)||errorNames.contains(name))throw ApiFailure.validation();
                var bytes=read(folder.resolve(name));if(bytes.length!=size.asLong()||!JournalMarkdown.hash(bytes).equals(digest))throw new ApiFailure(409,"JOURNAL_INCOMPLETE_TRANSFER");files.put(name,bytes);
            }
            try(var staged=Files.list(folder)){if(!staged.map(path->path.getFileName().toString()).sorted().toList().equals(new ArrayList<>(files.keySet())))throw new ApiFailure(409,"JOURNAL_INCOMPLETE_TRANSFER");}
        }catch(IOException unsafe){throw new ApiFailure(409,"JOURNAL_INCOMPLETE_TRANSFER");}
        // Idempotent handoff is fingerprinted by the importer even after a lost response.
        var batch=new JournalImport(store).seal(id,uuid(lease.get("deviceId")),lease.path("sequence").asLong(),files);
        new JournalImport(store).localErrors(id,uuid(lease.get("deviceId")),lease.path("sequence").asLong(),errors);
        for(String field:List.of("syncEpoch","configurationEpoch","accountVersion"))batch.set(field,lease.get(field));state.update(JOURNAL_BATCH,batch);
        state.update(JOURNAL_LEASE,lease.put("state","COMPLETED").put("manifestHash",manifestHash).put("completedAt",store.now().toString()));
        var device=state.required(JOURNAL_DEVICE,uuid(lease.get("deviceId")));state.update(JOURNAL_DEVICE,device.put("lastTransferAt",store.now().toString()));
        return object().put("status","COMPLETED").put("files",files.size());
    }
    void rescan(String owner){
        var deployment=configuration.load();if(deployment.owner(owner).isEmpty())throw new ApiFailure(503,"JOURNAL_ADMIN_SETUP_REQUIRED");existingAccount(owner);
        var ids=store.transaction(owner,()->new JournalImport(store).pending().stream().limit(100).toList());
        for(var id:ids)store.transaction(owner,()->{
            var state=new JournalState(store);var item=state.required(JOURNAL_ITEM,id);if(!item.path("state").asText().equals("PENDING"))return null;
            var batch=state.required(JOURNAL_BATCH,uuid(item.get("batchId")));var lease=state.required(JOURNAL_LEASE,uuid(batch.get("id")));
            if(!lease.path("state").asText().equals("COMPLETED"))throw new ApiFailure(409,"JOURNAL_INCOMPLETE_TRANSFER");
            try{valid(configuration.load(),batch);}
            catch(ApiFailure withdrawn){
                if(!Set.of("JOURNAL_DISABLED","JOURNAL_ACCOUNT_DISABLED","JOURNAL_DEVICE_REVOKED","JOURNAL_AUTHORIZATION_CHANGED").contains(withdrawn.code))throw withdrawn;
                state.update(JOURNAL_ITEM,item.put("state","ABORTED").put("error",withdrawn.code));return null;
            }
            var outcome=new JournalImport(store).process(id);
            if(Set.of("IMPORTED","UNCHANGED").contains(outcome.path("state").asText())){
                var device=state.required(JOURNAL_DEVICE,uuid(item.get("deviceId")));state.update(JOURNAL_DEVICE,device.put("lastImportAt",store.now().toString()));
            }return null;
        });
        store.transaction(owner,()->{new JournalRetention(store).cleanup(deployment);return null;});
    }
    void authorizeCandidate(UUID id){
        var state=new JournalState(store);var item=state.required(JOURNAL_ITEM,id);var batch=state.required(JOURNAL_BATCH,uuid(item.get("batchId")));valid(configuration.load(),batch);
    }
    ObjectNode settle(String username,String authorization,UUID id){
        String owner=gateway(username,authorization,gateway->{var value=lease(gateway,id,true);if(!value.path("state").asText().equals("COMPLETED"))throw ApiFailure.conflict();return gateway.account().owner();});
        rescan(owner);
        return gateway(username,authorization,gateway->{
            lease(gateway,id,true);var result=object().put("pending",0).put("conflicts",0).put("errors",0);
            for(var item:new JournalState(store).list(JOURNAL_ITEM))if(item.path("batchId").asText().equals(id.toString())){
                String field=switch(item.path("state").asText()){case "PENDING"->"pending";case "CONFLICT"->"conflicts";case "ERROR","ABORTED"->"errors";default->null;};if(field!=null)result.put(field,result.path(field).asInt()+1);
            }return result;
        });
    }
    private Path folder(JournalConfiguration.Deployment deployment,UUID id,boolean create){
        try{
            JournalConfiguration.privatePath(deployment.incoming(),true);
            Path account=deployment.incoming().resolve(hash(store.owner())),folder=account.resolve(id.toString());
            if(create){if(!Files.exists(account,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(account,PosixFilePermissions.asFileAttribute(DIRECTORY));}
            JournalConfiguration.privatePath(account,true);
            if(create)Files.createDirectory(folder,PosixFilePermissions.asFileAttribute(DIRECTORY));JournalConfiguration.privatePath(folder,true);return folder;
        }catch(IOException unsafe){throw new ApiFailure(503,"JOURNAL_PRIVATE_STORAGE_UNAVAILABLE");}
    }
    private static byte[] read(Path file)throws IOException{
        JournalConfiguration.privatePath(file,false);
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);var stream=java.nio.channels.Channels.newInputStream(channel)){
            byte[] bytes=stream.readNBytes(JournalMarkdown.FILE_BYTES+1);if(bytes.length>JournalMarkdown.FILE_BYTES)throw new IOException();return bytes;
        }
    }
    private static void syncDirectory(Path folder)throws IOException{try(var channel=FileChannel.open(folder,StandardOpenOption.READ)){channel.force(true);}}
    private static ObjectNode leaseReply(ObjectNode lease){return object().put("id",lease.path("id").asText()).put("status",lease.path("state").asText()).put("expiresAt",lease.path("expiresAt").asText());}
}
