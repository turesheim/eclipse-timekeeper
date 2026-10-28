package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.InstanceOperations.*;
import static net.resheim.timekeeper.server.JsonValues.parse;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Authorized durable backup lifecycle. HTTP routes must additionally enforce CSRF and grants. */
@ApplicationScoped
class InstanceBackupJobs {
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupService backups;
    @Inject AccountStore accounts;
    @Inject BackendClock clock;
    @ConfigProperty(name="timekeeper.instance-backup.storage",defaultValue="NONE") String storage;
    @ConfigProperty(name="timekeeper.instance-backup.maximum-archive-bytes",defaultValue="8589934592") long maximumBytes;
    @ConfigProperty(name="timekeeper.instance-backup.maximum-retained-bytes",defaultValue="17179869184") long retainedBytes;
    @ConfigProperty(name="timekeeper.instance-backup.maximum-jobs",defaultValue="3") int maximumJobs;

    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),work->{var thread=new Thread(work,"Timekeeper instance backup");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final class Pending implements Runnable {
        final InstanceAdministrator.Proof proof;final char[] phrase;final CompletableFuture<Status> started=new CompletableFuture<>();
        Pending(InstanceAdministrator.Proof proof,char[] phrase){this.proof=proof;this.phrase=phrase;}
        public void run(){try{create(proof,phrase,status->started.complete(status));}catch(Throwable failure){started.completeExceptionally(failure);}finally{Arrays.fill(phrase,'\0');}}
        void cancel(){Arrays.fill(phrase,'\0');started.completeExceptionally(new ApiFailure(503,"INSTANCE_BACKUP_STOPPING"));}
    }
    /** Starts a bounded worker and returns its durable receipt before snapshot IO. */
    Status start(InstanceAdministrator.Proof proof,char[] passphrase) {
        Pending work=null;boolean accepted=false;
        try {
            administrators.requireFresh(proof);if(passphrase==null||passphrase.length<12||passphrase.length>1024)throw ApiFailure.validation();
            work=new Pending(proof,passphrase.clone());
            try{workers.execute(work);accepted=true;}catch(RejectedExecutionException full){work.cancel();throw new ApiFailure(409,"INSTANCE_OPERATION_BUSY");}
            return work.started.get(30,TimeUnit.SECONDS);
        }catch(ApiFailure failure){throw failure;}
        catch(ExecutionException failure){if(failure.getCause() instanceof ApiFailure rejected)throw rejected;throw new ApiFailure(503,"INSTANCE_BACKUP_FAILED");}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new ApiFailure(503,"INSTANCE_BACKUP_START_PENDING");}
        catch(TimeoutException delayed){throw new ApiFailure(503,"INSTANCE_BACKUP_START_PENDING");}
        finally{if(!accepted&&work!=null)work.cancel();if(passphrase!=null)Arrays.fill(passphrase,'\0');}
    }
    @jakarta.annotation.PreDestroy void stop() {
        for(Runnable work:workers.shutdownNow())((Pending)work).cancel();
        try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }
    InstanceArtifacts artifacts() throws IOException {
        if(storage==null||storage.isBlank()||storage.equals("NONE"))throw new ApiFailure(503,"INSTANCE_STORAGE_REQUIRED");
        return new InstanceArtifacts(Path.of(storage),maximumBytes,retainedBytes,maximumJobs);
    }
    /** Synchronous worker entry; callers may schedule it without persisting its secrets. */
    Status create(InstanceAdministrator.Proof proof,char[] passphrase) {return create(proof,passphrase,status->{});}
    private Status create(InstanceAdministrator.Proof proof,char[] passphrase,java.util.function.Consumer<Status> started) {
        Lease lease=null;InstanceArtifacts files=null;ScheduledExecutorService renewal=null;boolean success=false;
        try {
            administrators.requireFresh(proof);
            if(passphrase==null||passphrase.length<12||passphrase.length>1024)throw ApiFailure.validation();
            files=artifacts();var selected=files;cleanup(proof,files);
            lease=accounts.tx(c->{lock(c);administrators.check(c,proof.principal());bindStorage(c,selected.identity());interruptAbandoned(c,clock.now());return begin(c,proof.principal().owner(),Type.BACKUP,clock.now());});
            started.accept(get(proof.principal(),lease.id()));
            var owned=lease;var lost=new AtomicReference<RuntimeException>();
            renewal=Executors.newSingleThreadScheduledExecutor(work->{var thread=new Thread(work,"Timekeeper instance backup lease");thread.setDaemon(true);return thread;});
            renewal.scheduleWithFixedDelay(()->{
                try {accounts.tx(c->{lock(c);administrators.check(c,proof.principal());renew(c,owned,State.PREPARING,clock.now());return null;});}
                catch(RuntimeException failure){lost.compareAndSet(null,new ApiFailure(409,"INSTANCE_OPERATION_LEASE_LOST"));}
            },60,60,TimeUnit.SECONDS);
            Path work=files.reserveBackup(lease.id());
            Path encrypted=backups.create(proof,passphrase,work);
            if(lost.get()!=null)throw lost.get();
            files.publishBackup(lease.id(),encrypted);
            // Only READY receipts permit download. A revocation or lost lease
            // after encryption cannot expose even a fully staged artifact.
            Status ready=accounts.tx(c->{lock(c);administrators.check(c,proof.principal());return advance(c,owned,State.PREPARING,State.READY,clock.now());});
            success=true;return ready;
        }catch(ApiFailure failure){throw failure;}
        catch(IOException failure){
            if("INSTANCE_ARTIFACT_QUOTA".equals(failure.getMessage()))throw new ApiFailure(413,"INSTANCE_ARCHIVE_TOO_LARGE");
            if("INSTANCE_ARTIFACT_BUSY".equals(failure.getMessage()))throw new ApiFailure(409,"INSTANCE_OPERATION_BUSY");
            throw new ApiFailure(503,"INSTANCE_BACKUP_FAILED");
        }
        catch(Exception failure){throw new ApiFailure(503,"INSTANCE_BACKUP_FAILED");}
        finally {
            if(passphrase!=null)Arrays.fill(passphrase,'\0');
            if(renewal!=null)renewal.shutdownNow();
            if(!success&&lease!=null) {
                var owned=lease;
                try {accounts.tx(c->{advance(c,owned,State.PREPARING,State.FAILED,clock.now());return null;});}
                catch(RuntimeException ignored){/* A lost lease stays recoverable as INTERRUPTED; never overwrite another worker. */}
                if(files!=null)try{files.remove(lease.id());}catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_CLEANUP_FAILED");}
            }
        }
    }
    void cleanup(InstanceAdministrator.Proof proof,InstanceArtifacts files) throws IOException {
        var obsolete=accounts.tx(c->{
            lock(c);administrators.check(c,proof.principal());bindStorage(c,files.identity());interruptAbandoned(c,clock.now());
            var ids=new ArrayList<UUID>();
            try(var statement=query(c,"SELECT id,payload FROM tk_instance WHERE kind='OPERATION'");var rows=statement.executeQuery()) {
                while(rows.next())if(Set.of("FAILED","INTERRUPTED","EXPIRED").contains(parse(rows.getString(2)).path("state").asText()))ids.add(UUID.fromString(rows.getString(1)));
            }catch(java.sql.SQLException failure){throw new ApiFailure(503,"INSTANCE_OPERATIONS_UNAVAILABLE");}
            return ids;
        });
        for(UUID id:obsolete)files.remove(id);
    }
    com.fasterxml.jackson.databind.node.ObjectNode summary(TokenRegistry.Principal principal) {
        return accounts.tx(c->{lock(c);administrators.check(c,principal);
            var result=JsonValues.object().put("storageConfigured",storage!=null&&!storage.isBlank()&&!storage.equals("NONE"))
                .put("maximumArchiveBytes",maximumBytes).put("chunkBytes",InstanceArtifacts.CHUNK_BYTES);
            result.set("items",recent(c,principal));return result;
        });
    }
    Status get(TokenRegistry.Principal principal,UUID id) {
        return accounts.tx(c->{lock(c);administrators.check(c,principal);return status(c,id);});
    }
    <T> T read(InstanceAdministrator.Proof proof,UUID id,InstanceArtifacts.Reader<T> reader) throws IOException {
        administrators.requireFresh(proof);var principal=proof.principal();var files=artifacts();
        accounts.tx(c->{lock(c);administrators.check(c,principal);bindStorage(c,files.identity());var current=status(c,id);
            if(current.type()!=Type.BACKUP||current.state()!=State.READY)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");return null;});
        return files.read(id,(input,bytes)->{
            administrators.requireFresh(proof);var current=get(principal,id);
            if(current.type()!=Type.BACKUP||current.state()!=State.READY)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            return reader.read(input,bytes);
        });
    }
    Status discard(InstanceAdministrator.Proof proof,UUID id) {
        administrators.requireFresh(proof);
        try {
            var files=artifacts();Status expired=accounts.tx(c->{lock(c);administrators.check(c,proof.principal());bindStorage(c,files.identity());var current=status(c,id);
                if(current.state()==State.EXPIRED)return current;return expire(c,id,proof.principal().owner(),clock.now());});
            files.remove(id);return expired;
        }catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_CLEANUP_FAILED");}
    }
}
