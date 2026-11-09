package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Background pulls with cross-replica claims, bounded I/O outside JDBC and generation checks before writes. */
@ApplicationScoped
class TaskProviderRefresh {
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    @Inject BackendClock clock;
    @Inject AgroalDataSource datasource;
    @Inject InstanceRestoreFence restoreFence;
    @Inject TaskProviderConfiguration configuration;
    @Inject TaskProviderClients clients;
    @ConfigProperty(name="timekeeper.tasks.automatic",defaultValue="true") boolean automatic;
    private final Semaphore capacity=new Semaphore(4);
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(run->{var thread=new Thread(run,"task-provider-refresh");thread.setDaemon(true);return thread;});
    private volatile boolean closed;
    void start(@Observes StartupEvent event){scheduler.scheduleWithFixedDelay(this::sweep,1,60,TimeUnit.SECONDS);}
    @PreDestroy void close(){closed=true;scheduler.shutdownNow();workers.shutdownNow();}
    void wake(){if(!closed)try{scheduler.execute(this::sweep);}catch(RejectedExecutionException ignored){}}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    ApiReplies refresh(RequestIdentity identity,UUID id){
        if(closed||!capacity.tryAcquire())throw new ApiFailure(503,"TASK_PROVIDER_BUSY");
        try{
            var claim=store.authenticated(identity,()->{
                var value=connections().required(id);var connection=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);
                configuration.settings(TaskProviderConfiguration.provider(connection.path("providerId").asText()));
                return connections().claim(id,true);
            });
            if(claim!=null)submit(claim);else capacity.release();
        }catch(RuntimeException failure){capacity.release();throw failure;}
        return store.authenticated(identity,()->ApiReplies.resource(202,connections().visible(connections().required(id)),null));
    }
    private void submit(TaskProviderConnections.Claim claim){
        try{workers.submit(()->{try{execute(claim);}finally{capacity.release();wake();}});}
        catch(RejectedExecutionException failure){capacity.release();/* Its lease expires; no result is published. */}
    }
    /** Also used by server-side tests through the same fixed-endpoint client factory. */
    void execute(TaskProviderConnections.Claim claim){
        ObjectNode outcome=null;String error=null;int retry=0;
        try{
            if(!store.transaction(claim.owner(),()->connections().active(claim)!=null))return;
            var settings=configuration.settings(claim.provider());
            if(!TaskProviderConnections.settingsHash(settings).equals(claim.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
            var tokens=claim.tokens();
            if(!tokens.expires().isAfter(clock.now().plusSeconds(60))){
                tokens=clients.oauth(claim.provider()).refresh(settings,tokens,clock.now());var renewed=tokens;
                if(!store.transaction(claim.owner(),()->connections().credentials(claim,renewed)))return;
            }
            var provider=clients.cloud(claim.provider());
            if(claim.discover()){
                if(!provider.account(tokens.access()).id().equals(claim.account()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
                var catalog=provider.catalog(tokens.access());if(!store.transaction(claim.owner(),()->connections().catalog(claim,catalog)))return;
            }
            if(claim.catalogOnly())outcome=object();
            else{
                if(claim.list()==null)throw new ApiFailure(409,"TASK_PROVIDER_LIST_MISSING");
                var scan=TaskCloudPull.fetch(provider,tokens.access(),claim.account(),claim.remoteList(),clock.now());
                outcome=store.transaction(claim.owner(),()->connections().active(claim)==null?null:new TaskCloudPull(store,receipts,inputs).apply(claim.id(),claim.list(),scan));
            }
        }catch(TaskProviderHttp.Failure failure){error=failure.getMessage();retry=failure.retryAfterSeconds;}
        catch(ApiFailure failure){error=failure.code;}
        catch(RuntimeException failure){error="TASK_PROVIDER_UNAVAILABLE";}
        var result=outcome;String failed=error;int delay=retry;
        try{store.transaction(claim.owner(),()->connections().finish(claim,result,failed,delay));}
        catch(RuntimeException failure){/* Recovery/restore or a database outage leaves the lease for retry. */}
    }
    private record Due(String owner,UUID id,Instant next){}
    void sweep(){
        if(closed||capacity.availablePermits()==0)return;
        try{
            var due=new ArrayList<Due>();Instant now=clock.now();
            try(var connection=datasource.getConnection();var held=restoreFence.enter(connection);
                    var query=connection.prepareStatement("SELECT r.owner_id,r.id,r.payload,c.payload FROM tk_resource r JOIN tk_resource c ON c.owner_id=r.owner_id AND c.id=r.id AND c.entity_type='TASK_CONNECTION' JOIN tk_account a ON a.owner_id=r.owner_id WHERE r.entity_type='TASK_AUTH' AND a.state<>'DISABLED'");var rows=query.executeQuery()){
                while(rows.next()){
                    var value=parse(rows.getString(3));var metadata=parse(rows.getString(4));
                    if(!metadata.path("enabled").asBoolean()||!metadata.path("readable").asBoolean()||value.path("blocked").asBoolean()||value.path("selected").isEmpty()
                            ||!automatic&&!value.path("requested").asBoolean()||!value.hasNonNull("nextRefresh"))continue;
                    var provider=TaskProviderConfiguration.provider(metadata.path("providerId").asText());if(!configuration.available(provider))continue;
                    Instant next=Instant.parse(value.path("nextRefresh").asText());if(!next.isAfter(now))due.add(new Due(rows.getString(1),rows.getObject(2,UUID.class),next));
                }
            }
            due.sort(Comparator.comparing(Due::next).thenComparing(Due::owner).thenComparing(Due::id));
            for(var value:due){
                if(closed||!capacity.tryAcquire())break;
                TaskProviderConnections.Claim claim;
                try{claim=store.transaction(value.owner(),()->connections().claim(value.id(),false));}
                catch(RuntimeException failure){capacity.release();continue;}
                if(claim==null){capacity.release();continue;}submit(claim);
            }
        }catch(SQLException|RuntimeException failure){/* A later sweep retries; credentials never enter log messages. */}
    }
}
