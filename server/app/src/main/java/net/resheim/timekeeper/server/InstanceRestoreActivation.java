package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.InstanceOperations.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Prepare private files asynchronously; explicit confirmation commits consent, never replaces live data. */
@ApplicationScoped
class InstanceRestoreActivation {
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupJobs jobs;
    @Inject AccountStore accounts;
    @Inject BackendClock clock;
    @ConfigProperty(name="timekeeper.instance-restore.storage",defaultValue="NONE") String storage;
    @ConfigProperty(name="timekeeper.instance-backup.maximum-expanded-bytes",defaultValue="17179869184") long maximumExpandedBytes;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),work->{var thread=new Thread(work,"Timekeeper restore staging");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final class Staging implements Runnable {
        final InstanceAdministrator.Proof proof;final UUID id;final long version;final char[] phrase;final InstanceRestoreDatabase.Deployment deployment;
        final CompletableFuture<ObjectNode> started=new CompletableFuture<>();
        Staging(InstanceAdministrator.Proof proof,UUID id,long version,char[] phrase,InstanceRestoreDatabase.Deployment deployment){this.proof=proof;this.id=id;this.version=version;this.phrase=phrase;this.deployment=deployment;}
        public void run(){try{stage(this);}catch(Throwable failure){started.completeExceptionally(failure);}finally{Arrays.fill(phrase,'\0');}}
        void cancel(){Arrays.fill(phrase,'\0');started.completeExceptionally(new ApiFailure(503,"INSTANCE_BACKUP_STOPPING"));}
    }
    private InstanceRestoreIntent intent() throws IOException {
        if(storage.equals("NONE"))throw new ApiFailure(503,"INSTANCE_STORAGE_REQUIRED");
        return new InstanceRestoreIntent(Path.of(storage),Math.addExact(maximumExpandedBytes,128_000_000L));
    }
    private String packageId(){return ConfigProvider.getConfig().getOptionalValue("timekeeper.package-id",String.class).orElse("timekeeper");}
    private ObjectNode summary(InstanceRestoreDatabase.Deployment deployment,InstanceConfiguration.Snapshot target) {
        var properties=target.document().path("properties");var config=ConfigProvider.getConfig();
        return object().put("packageId",deployment.packageId()).put("publicOrigin",deployment.publicOrigin()).put("passkeyRpId",deployment.passkeyRpId()).put("passkeysEnabled",deployment.passkeysEnabled())
            .put("databaseType",properties.path("quarkus.datasource.db-kind").asText()).put("httpHost",config.getOptionalValue("quarkus.http.host",String.class).orElse("0.0.0.0"))
            .put("httpPort",config.getOptionalValue("quarkus.http.port",Integer.class).orElse(8080)).put("httpsPort",config.getOptionalValue("quarkus.http.ssl-port",Integer.class).orElse(8443));
    }
    ObjectNode get(TokenRegistry.Principal principal,UUID id) {
        administrators.require(principal);return accounts.tx(c->{lock(c);administrators.check(c,principal);interruptAbandoned(c,clock.now());return stagingInfo(c,id,principal);});
    }
    ObjectNode start(InstanceAdministrator.Proof proof,UUID id,long version,char[] phrase,String origin,String rp,boolean passkeys) {
        Staging task=null;boolean accepted=false;
        try {
            administrators.requireFresh(proof);if(storage.equals("NONE"))throw new ApiFailure(503,"INSTANCE_STORAGE_REQUIRED");
            if(phrase==null||phrase.length<12||phrase.length>1024||version<1||version>=9007199254740987L)throw ApiFailure.validation();
            task=new Staging(proof,id,version,phrase.clone(),new InstanceRestoreDatabase.Deployment(packageId(),origin,rp,passkeys));
            try{workers.execute(task);accepted=true;}catch(RejectedExecutionException busy){throw new ApiFailure(409,"INSTANCE_OPERATION_BUSY");}
            return task.started.get(30,TimeUnit.SECONDS);
        }catch(ExecutionException failure){if(failure.getCause() instanceof ApiFailure rejected)throw rejected;throw new ApiFailure(503,"INSTANCE_VALIDATION_FAILED");}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new ApiFailure(503,"INSTANCE_VALIDATION_START_PENDING");}
        catch(TimeoutException pending){throw new ApiFailure(503,"INSTANCE_VALIDATION_START_PENDING");}
        finally{if(!accepted&&task!=null)task.cancel();if(phrase!=null)Arrays.fill(phrase,'\0');}
    }
    private void stage(Staging task) throws IOException {
        Lease lease=null;InstanceArtifacts files=null;ScheduledExecutorService renewal=null;boolean complete=false;
        try(var target=InstanceConfiguration.captureRuntime()) {
            var intent=intent();files=jobs.artifacts();var selected=files;UUID generation=UUID.randomUUID();var previous=new UUID[1];
            var targetSummary=summary(task.deployment,target);
            accounts.tx(c->{try {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);lock(c);administrators.check(c,task.proof.principal());bindStorage(c,selected.identity());uploadInfo(c,task.id,task.proof.principal());
                var current=status(c,task.id);if(current.version()!=task.version)throw new ApiFailure(412,"STALE_VERSION");
                if(current.state()!=State.REVIEW||!Set.of("NONE","FAILED").contains(stagingInfo(c,task.id,task.proof.principal()).path("phase").asText()))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
                intent.cleanupOrphans(c,administrators,task.proof,task.id,clock.now());return null;
            }catch(SQLException failure){throw databaseFailure(failure);}catch(IOException failure){throw failure(failure);}});
            lease=serializable(c->{try {
                lock(c);administrators.check(c,task.proof.principal());bindStorage(c,selected.identity());
                if(!InstanceRestoreTarget.inspect(c,task.proof.principal().owner()).databaseEligible())throw new ApiFailure(409,"INSTANCE_TARGET_NOT_EMPTY");
                try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT payload FROM tk_instance WHERE kind='CONTROL'")){if(!rows.next())throw new SQLException();var value=parse(rows.getString(1));if(value.hasNonNull("restoreGeneration"))previous[0]=UUID.fromString(value.path("restoreGeneration").asText());}
                return beginStaging(c,task.id,task.proof.principal(),task.version,generation,targetSummary,clock.now());
            }catch(SQLException failure){throw databaseFailure(failure);}});
            var owned=lease;task.started.complete(get(task.proof.principal(),task.id));
            var lost=new AtomicBoolean();renewal=Executors.newSingleThreadScheduledExecutor(work->{var thread=new Thread(work,"Timekeeper restore staging lease");thread.setDaemon(true);return thread;});
            renewal.scheduleWithFixedDelay(()->{try{accounts.tx(c->{lock(c);administrators.check(c,task.proof.principal());renewStaging(c,owned,clock.now());return null;});}catch(RuntimeException failure){lost.set(true);}},60,60,TimeUnit.SECONDS);
            selected.clearWork(task.id);Path work=selected.validationWork(task.id);
            selected.inspect(task.id,(encrypted,length)->{
                try(var archive=InstanceBackupArchive.open(encrypted,task.phrase,work,maximumExpandedBytes)) {
                    var spec=new InstanceRestoreIntent.Spec(generation,task.id,task.version+2,task.proof.principal().owner(),selected.identity(),previous[0],task.deployment);
                    try(var staged=intent.stage(spec,archive,target,clock.now())) {
                        InstanceRestoreMaterial.install(staged,archive);
                        if(lost.get())throw new ApiFailure(409,"INSTANCE_OPERATION_LEASE_LOST");
                        serializable(c->{try {
                            lock(c);administrators.check(c,task.proof.principal());
                            if(!InstanceRestoreTarget.inspect(c,task.proof.principal().owner()).databaseEligible())throw new ApiFailure(409,"INSTANCE_TARGET_NOT_EMPTY");
                            finishStaging(c,owned,spec.reviewVersion(),generation,staged.digest(),clock.now());return null;
                        }catch(SQLException failure){throw databaseFailure(failure);}});
                    }
                }
                return null;
            });
            complete=true;
        }finally {
            if(renewal!=null)renewal.shutdownNow();
            IOException cleanup=null;
            // Only an acquired lease owns this workspace. Finish cleanup before
            // exposing FAILED so a retry cannot race an older worker's deletion.
            if(files!=null&&lease!=null)try{files.clearWork(task.id);}catch(IOException failure){cleanup=failure;}
            if(!complete&&lease!=null){var owned=lease;try{accounts.tx(c->{failStaging(c,owned,clock.now());return null;});}catch(RuntimeException ignored){/* Durable worker fencing owns any newer outcome. */}}
            if(cleanup!=null)throw cleanup;
        }
    }
    ObjectNode confirm(InstanceAdministrator.Proof proof,UUID id,long version,UUID generation,String confirmation) {
        administrators.requireFresh(proof);
        try(var current=InstanceConfiguration.captureRuntime();var staged=intent().staged()) {
            if(!staged.spec().operation().equals(id)||!staged.spec().generation().equals(generation)||staged.spec().reviewVersion()!=version)throw new ApiFailure(412,"STALE_VERSION");
            if(!staged.spec().deployment().packageId().equals(packageId())||!confirmation.equals("RESTORE "+packageId()))throw ApiFailure.validation();
            long committed=serializable(c->{try {
                lock(c);administrators.check(c,proof.principal());
                var stage=stagingInfo(c,id,proof.principal());if(!stage.path("phase").asText().equals("READY"))throw new ApiFailure(409,"INSTANCE_OPERATION_STATE");
                return staged.authorize(c,administrators,proof,current,clock.now());
            }catch(SQLException failure){throw databaseFailure(failure);}catch(IOException failure){throw failure(failure);}});
            return object().put("id",id.toString()).put("generation",generation.toString()).put("operationVersion",committed).put("phase","PENDING_RESTART").put("publicOrigin",staged.spec().deployment().publicOrigin());
        }catch(IOException failure){throw failure(failure);}
    }
    /** Retry only rolled-back SQL snapshot conflicts, never file I/O or uncertain commits. */
    private <T> T serializable(java.util.function.Function<Connection,T> operation) {
        for(int attempt=0;;attempt++) {
            try{return accounts.tx(c->{try{c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);}catch(SQLException failure){throw databaseFailure(failure);}return operation.apply(c);});}
            catch(ApiFailure failure){if(attempt>=4||!snapshotConflict(failure))throw failure;}
        }
    }
    private static boolean snapshotConflict(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&Set.of("40001","40P01").contains(sql.getSQLState()))return true;return false;
    }
    private static ApiFailure failure(IOException failure){return new ApiFailure(failure.getMessage()!=null&&failure.getMessage().equals("INSTANCE_RESTORE_BUSY")?409:503,failure.getMessage()!=null&&failure.getMessage().equals("INSTANCE_RESTORE_BUSY")?"INSTANCE_OPERATION_BUSY":"INSTANCE_VALIDATION_FAILED");}
    @jakarta.annotation.PreDestroy void stop(){for(Runnable task:workers.shutdownNow())((Staging)task).cancel();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
}
