package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.InstanceOperations.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Session-bound ciphertext upload and archive-only review. Never activates a restore. */
@ApplicationScoped
class InstanceRestoreUploads {
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupJobs jobs;
    @Inject AccountStore accounts;
    @Inject BackendClock clock;
    @ConfigProperty(name="timekeeper.instance-backup.maximum-expanded-bytes",defaultValue="17179869184") long maximumExpandedBytes;

    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{var thread=new Thread(task,"Timekeeper instance validation");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final class Validation implements Runnable {
        final InstanceAdministrator.Proof proof;final UUID id;final char[] phrase;final CompletableFuture<ObjectNode> started=new CompletableFuture<>();
        Validation(InstanceAdministrator.Proof proof,UUID id,char[] phrase){this.proof=proof;this.id=id;this.phrase=phrase;}
        public void run(){try{validate(proof,id,phrase,value->started.complete(value));}catch(Throwable failure){started.completeExceptionally(failure);}finally{Arrays.fill(phrase,'\0');}}
        void cancel(){Arrays.fill(phrase,'\0');started.completeExceptionally(new ApiFailure(503,"INSTANCE_BACKUP_STOPPING"));}
    }
    ObjectNode startValidation(InstanceAdministrator.Proof proof,UUID id,char[] phrase) {
        Validation task=null;boolean accepted=false;
        try {
            administrators.requireFresh(proof);if(phrase==null||phrase.length<12||phrase.length>1024)throw ApiFailure.validation();
            task=new Validation(proof,id,phrase.clone());try{workers.execute(task);accepted=true;}catch(RejectedExecutionException busy){throw new ApiFailure(409,"INSTANCE_OPERATION_BUSY");}
            return task.started.get(30,TimeUnit.SECONDS);
        }catch(ExecutionException failure){if(failure.getCause() instanceof ApiFailure rejected)throw rejected;throw new ApiFailure(503,"INSTANCE_VALIDATION_FAILED");}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new ApiFailure(503,"INSTANCE_VALIDATION_START_PENDING");}
        catch(TimeoutException delayed){throw new ApiFailure(503,"INSTANCE_VALIDATION_START_PENDING");}
        finally{if(!accepted&&task!=null)task.cancel();if(phrase!=null)Arrays.fill(phrase,'\0');}
    }
    @jakarta.annotation.PreDestroy void stop(){for(Runnable task:workers.shutdownNow())((Validation)task).cancel();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    ObjectNode create(InstanceAdministrator.Proof proof,long bytes,String digest) {
        administrators.requireFresh(proof);if(bytes<40||digest==null||!digest.matches("[a-f0-9]{64}"))throw ApiFailure.validation();
        InstanceArtifacts files=null;Lease lease=null;
        try {
            files=jobs.artifacts();jobs.cleanup(proof,files);var selected=files;
            lease=accounts.tx(c->{lock(c);administrators.check(c,proof.principal());bindStorage(c,selected.identity());return beginUpload(c,proof.principal(),bytes,digest,clock.now());});
            files.create(lease.id(),bytes,digest);return get(proof.principal(),lease.id());
        }catch(IOException failure){fail(lease);if(files!=null&&lease!=null)remove(files,lease.id());throw io(failure);}
        catch(RuntimeException failure){fail(lease);if(files!=null&&lease!=null)remove(files,lease.id());throw failure;}
    }
    ObjectNode get(TokenRegistry.Principal principal,UUID id) {
        administrators.require(principal);
        try {
            var files=jobs.artifacts();var result=accounts.tx(c->{lock(c);administrators.check(c,principal);bindStorage(c,files.identity());interruptAbandoned(c,clock.now());var view=uploadInfo(c,id,principal);view.set("operation",representation(status(c,id)));return view;});
            var state=State.valueOf(result.path("operation").path("state").asText());result.put("chunkBytes",InstanceArtifacts.CHUNK_BYTES);
            if(Set.of(State.EXPIRED,State.FAILED,State.INTERRUPTED).contains(state))return result.put("received",0).put("complete",false);
            if(state==State.VALIDATING||state==State.REVIEW)return result.put("received",result.path("bytes").asLong()).put("complete",true);
            var progress=files.status(id);return result.put("received",progress.received()).put("complete",progress.received()==progress.bytes());
        }catch(IOException failure){throw io(failure);}
    }
    /** Advisory target check; never changes application data or authorizes activation. */
    ObjectNode target(InstanceAdministrator.Proof proof,UUID id) {
        administrators.requireFresh(proof);
        return accounts.tx(c->{
            try {c.setTransactionIsolation(java.sql.Connection.TRANSACTION_SERIALIZABLE);}
            catch(java.sql.SQLException failure){throw new ApiFailure(503,"INSTANCE_TARGET_UNAVAILABLE");}
            lock(c);administrators.check(c,proof.principal());uploadInfo(c,id,proof.principal());var operation=status(c,id);
            if(operation.state()!=State.REVIEW)throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
            try {
                var check=InstanceRestoreTarget.inspect(c,proof.principal().owner());
                var result=object().put("id",id.toString()).put("operationVersion",operation.version()).put("databaseEligible",check.databaseEligible()).put("restoreReady",false).put("checkedAt",clock.now().toString());
                var blockers=result.putArray("blockers");check.blockers().forEach(value->blockers.add(value.name()));
                result.putArray("requiredSteps").add("OFFLINE_TARGET_RECHECK").add("AUTHENTICATION_CONFIGURATION").add("DEPLOYMENT_MAPPING").add("OFFLINE_ACTIVATION");return result;
            }catch(java.sql.SQLException failure){throw new ApiFailure(503,"INSTANCE_TARGET_UNAVAILABLE");}
        });
    }
    ObjectNode append(TokenRegistry.Principal principal,UUID id,long offset,InputStream body) {
        administrators.require(principal);if(offset<0)throw ApiFailure.validation();
        var lease=accounts.tx(c->{lock(c);administrators.check(c,principal);return uploadLease(c,id,principal,clock.now());});
        try {
            byte[] chunk=body.readNBytes(InstanceArtifacts.CHUNK_BYTES+1);
            if(chunk.length<1)throw ApiFailure.validation();if(chunk.length>InstanceArtifacts.CHUNK_BYTES)throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            var files=jobs.artifacts();accounts.tx(c->{lock(c);administrators.check(c,principal);bindStorage(c,files.identity());return null;});
            files.append(id,offset,chunk);accounts.tx(c->{lock(c);administrators.check(c,principal);renew(c,lease,State.UPLOADING,clock.now());return null;});return get(principal,id);
        }catch(IOException failure){throw io(failure);}
    }
    /** All plaintext is scoped to this operation and removed before REVIEW. */
    ObjectNode validate(InstanceAdministrator.Proof proof,UUID id,char[] phrase) {return validate(proof,id,phrase,value->{});}
    private ObjectNode validate(InstanceAdministrator.Proof proof,UUID id,char[] phrase,java.util.function.Consumer<ObjectNode> started) {
        Lease lease=null;InstanceArtifacts files=null;ScheduledExecutorService renewal=null;boolean completed=false;
        try {
            administrators.requireFresh(proof);if(phrase==null||phrase.length<12||phrase.length>1024)throw ApiFailure.validation();
            if(maximumExpandedBytes<1)throw new ApiFailure(503,"INSTANCE_LIMITS_INVALID");
            var selected=jobs.artifacts();files=selected;
            lease=accounts.tx(c->{lock(c);administrators.check(c,proof.principal());bindStorage(c,selected.identity());return uploadLease(c,id,proof.principal(),clock.now());});
            var progress=files.status(id);if(progress.received()!=progress.bytes())throw ApiFailure.validation();var owned=lease;
            accounts.tx(c->{lock(c);administrators.check(c,proof.principal());advance(c,owned,State.UPLOADING,State.VALIDATING,clock.now());return null;});
            started.accept(get(proof.principal(),id));
            var lost=new AtomicBoolean();renewal=Executors.newSingleThreadScheduledExecutor(work->{var thread=new Thread(work,"Timekeeper instance validation lease");thread.setDaemon(true);return thread;});
            renewal.scheduleWithFixedDelay(()->{try{accounts.tx(c->{lock(c);administrators.check(c,proof.principal());renew(c,owned,State.VALIDATING,clock.now());return null;});}catch(RuntimeException failure){lost.set(true);}},60,60,TimeUnit.SECONDS);
            ObjectNode review;
            try {
                selected.complete(id);Path work=selected.validationWork(id);
                review=files.inspect(id,(encrypted,length)->{
                    try(var verified=InstanceBackupArchive.open(encrypted,phrase,work,maximumExpandedBytes)) {
                        var result=object().put("archiveVerified",true).put("restoreReady",false).put("archiveId",verified.manifest().path("archiveId").asText()).put("createdAt",verified.manifest().path("createdAt").asText()).put("schema",verified.manifest().path("schema").asInt());
                        result.set("source",verified.manifest().path("source").deepCopy());var counts=result.putObject("counts");verified.counts().forEach(counts::put);
                        result.putArray("requiredSteps").add("TARGET_READINESS").add("AUTHENTICATION_CONFIGURATION").add("DEPLOYMENT_MAPPING").add("OFFLINE_ACTIVATION");return result;
                    }
                });
            }catch(IOException invalid){files.clearWork(id);accounts.tx(c->{lock(c);administrators.check(c,proof.principal());rejectValidation(c,owned,clock.now(),invalid instanceof InstanceArchiveRejection rejection&&rejection.code()==InstanceArchiveRejection.Code.INVALID_INSTANCE_AUTHENTICATION);return null;});completed=true;return get(proof.principal(),id);}
            files.clearWork(id);if(lost.get())throw new ApiFailure(409,"INSTANCE_OPERATION_LEASE_LOST");
            accounts.tx(c->{lock(c);administrators.check(c,proof.principal());finishReview(c,owned,review,clock.now());return null;});completed=true;return get(proof.principal(),id);
        }catch(IOException failure){throw io(failure);}
        finally {
            if(phrase!=null)Arrays.fill(phrase,'\0');if(renewal!=null)renewal.shutdownNow();
            if(!completed&&lease!=null){var owned=lease;try{accounts.tx(c->{var current=status(c,id);if(current.state()==State.VALIDATING)advance(c,owned,State.VALIDATING,State.FAILED,clock.now());return null;});}catch(RuntimeException ignored){}if(files!=null)try{files.clearWork(id);}catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_CLEANUP_FAILED");}}
        }
    }
    static ObjectNode representation(Status value){return object().put("id",value.id().toString()).put("type",value.type().name()).put("state",value.state().name()).put("version",value.version()).put("updatedAt",value.updatedAt().toString());}
    private void fail(Lease lease){if(lease==null)return;try{accounts.tx(c->{advance(c,lease,State.UPLOADING,State.FAILED,clock.now());return null;});}catch(RuntimeException ignored){}}
    private static void remove(InstanceArtifacts files,UUID id){try{files.remove(id);}catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_CLEANUP_FAILED");}}
    private static ApiFailure io(IOException failure){return switch(failure.getMessage()==null?"":failure.getMessage()){case "INSTANCE_ARTIFACT_QUOTA"->new ApiFailure(413,"INSTANCE_ARCHIVE_TOO_LARGE");case "INSTANCE_ARTIFACT_BUSY"->new ApiFailure(409,"INSTANCE_OPERATION_BUSY");case "INSTANCE_ARTIFACT_CONFLICT"->new ApiFailure(409,"INSTANCE_UPLOAD_CONFLICT");case "INVALID_INSTANCE_ARTIFACT"->ApiFailure.validation();default->new ApiFailure(503,"INSTANCE_BACKUP_UNAVAILABLE");};}
}
