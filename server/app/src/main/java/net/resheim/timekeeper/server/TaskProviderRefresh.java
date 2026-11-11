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
    private final java.util.concurrent.atomic.AtomicBoolean revoking=new java.util.concurrent.atomic.AtomicBoolean();
    void start(@Observes StartupEvent event){scheduler.scheduleWithFixedDelay(this::sweep,1,60,TimeUnit.SECONDS);}
    @PreDestroy void close(){closed=true;scheduler.shutdownNow();workers.shutdownNow();}
    void wake(){if(!closed)try{scheduler.execute(this::sweep);}catch(RejectedExecutionException ignored){}}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    ApiReplies refresh(RequestIdentity identity,UUID id){
        if(closed||!capacity.tryAcquire())throw new ApiFailure(503,"TASK_PROVIDER_BUSY");
        try{
            var claim=store.authenticated(identity,()->{
                var value=connections().required(id);var connection=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);
                if(!value.hasNonNull("brokerEndpoint"))configuration.settings(TaskProviderConfiguration.provider(connection.path("providerId").asText()));
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
            var tokens=claim.tokens();
            if(claim.broker()!=null){
                if(!TaskProviderConnections.sharedHash(claim.broker()).equals(claim.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
                if(!tokens.expires().isAfter(clock.now().plusSeconds(60))){
                    var reply=clients.shared(SharedConnectConfiguration.validate(claim.broker())).refresh(tokens.refresh());
                    if(!reply.path("provider").asText().equals(new TaskCloudProvider(claim.provider()).id())||!reply.path("remoteAccountId").asText().equals(claim.account())||!reply.path("services").equals(claim.services()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
                    tokens=SharedConnectClient.tokens(reply,tokens.refresh());var renewed=tokens;if(!store.transaction(claim.owner(),()->connections().credentials(claim,renewed)))return;
                }
            }else{
                var settings=configuration.settings(claim.provider());if(!TaskProviderConnections.settingsHash(settings).equals(claim.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
                if(!tokens.expires().isAfter(clock.now().plusSeconds(60))){tokens=clients.oauth(claim.provider()).refresh(settings,tokens,clock.now(),claim.writable());var renewed=tokens;if(!store.transaction(claim.owner(),()->connections().credentials(claim,renewed)))return;}
            }
            var provider=clients.cloud(claim.provider());
            RuntimeException taskCatalogFailure=null;
            if(claim.discover()){
                if(!provider.account(tokens.access()).id().equals(claim.account()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
                if(claim.services().path("tasks").asBoolean()){
                    try{var catalog=provider.catalog(tokens.access());if(!store.transaction(claim.owner(),()->connections().catalog(claim,catalog)))return;}
                    catch(RuntimeException failure){taskCatalogFailure=failure;}
                }
                if(claim.services().path("calendars").asBoolean()){
                    try{var calendars=clients.calendars(claim.provider()).catalog(tokens.access());store.transaction(claim.owner(),()->{if(connections().active(claim)!=null)new InternetCalendars(store).catalog(claim.id(),calendars);return null;});}
                    catch(RuntimeException failure){store.transaction(claim.owner(),()->{if(connections().active(claim)!=null)new InternetCalendars(store).error(claim.id(),InternetCalendars.failure(failure));return null;});}
                }
            }
            if(claim.services().path("calendars").asBoolean()&&(claim.discover()||store.transaction(claim.owner(),()->{var auth=connections().active(claim);return auth!=null&&auth.path("calendarPending").asBoolean();})))new InternetCalendars(store).synchronize(claim,clients.calendars(claim.provider()),tokens.access());
            if(taskCatalogFailure!=null)throw taskCatalogFailure;
            if(claim.catalogOnly())outcome=object();
            else{
                if(claim.list()==null)throw new ApiFailure(409,"TASK_PROVIDER_LIST_MISSING");
                if(!provider.account(tokens.access()).id().equals(claim.account()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
                var scans=new TaskProviderScans(store,receipts,inputs);
                var scan=store.transaction(claim.owner(),()->scans.begin(claim));long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
                while(scan!=null&&scan.path("state").asText().equals("FETCHING")){
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new ApiFailure(503,"TASK_PROVIDER_SCAN_INCOMPLETE");
                    if(!store.transaction(claim.owner(),()->connections().active(claim)!=null))return;
                    var page=provider.tasks(tokens.access(),claim.remoteList(),optionalText(scan.get("next")).orElse(null),optionalText(scan.get("updatedMin")).map(Instant::parse).orElse(null));
                    var expected=scan;scan=store.transaction(claim.owner(),()->scans.acknowledge(claim,expected,page));
                }
                if(scan==null)return;
                outcome=store.transaction(claim.owner(),()->scans.reconcile(claim));
                if(claim.provider()==TaskCloudProvider.Provider.GOOGLE)new TaskProviderWrites(store,receipts,inputs).drain(claim,tokens.access(),clients.googleWriter());
            }
        }catch(TaskProviderHttp.Failure failure){error=failure.getMessage();retry=failure.retryAfterSeconds;}
        catch(ApiFailure failure){error=failure.code;}
        catch(RuntimeException failure){error="TASK_PROVIDER_UNAVAILABLE";}
        var result=outcome;String failed=error;int delay=retry;
        try{store.transaction(claim.owner(),()->connections().finish(claim,result,failed,delay));}
        catch(RuntimeException failure){/* Recovery/restore or a database outage leaves the lease for retry. */}
    }
    private record Due(String owner,UUID id,Instant next){}
    private void revokeSharedGrants(){
        if(!revoking.compareAndSet(false,true))return;
        try{workers.submit(()->{try{drainSharedRevocations();}finally{revoking.set(false);}});}catch(RejectedExecutionException closed){revoking.set(false);}
    }
    private void drainSharedRevocations(){
        var pending=new ArrayList<Due>();
        try(var connection=datasource.getConnection();var held=restoreFence.enter(connection);var query=connection.prepareStatement("SELECT owner_id,id,payload FROM tk_resource WHERE entity_type=? ORDER BY owner_id,id")){
            query.setString(1,SharedConnectRevocations.KIND);query.setFetchSize(20);try(var rows=query.executeQuery()){while(rows.next()&&pending.size()<20){var value=parse(rows.getString(3));if(Instant.parse(value.path("nextAttempt").asText()).isAfter(clock.now())||value.hasNonNull("leaseUntil")&&Instant.parse(value.path("leaseUntil").asText()).isAfter(clock.now()))continue;pending.add(new Due(rows.getString(1),rows.getObject(2,UUID.class),clock.now()));}}
        }catch(SQLException|RuntimeException failure){return;}
        int attempted=0;for(var item:pending){
            if(closed||attempted>=4)break;ObjectNode claimed;
            try{claimed=store.transaction(item.owner(),()->new SharedConnectRevocations(store).claim(item.id()));}catch(RuntimeException failure){continue;}if(claimed==null)continue;attempted++;
            boolean complete=false;
            try{clients.shared(SharedConnectConfiguration.validate(claimed.path("endpoint").asText())).disconnect(claimed.path("credential").asText());complete=true;}
            catch(TaskProviderHttp.Failure failure){complete=failure.status==401||failure.status==404;}
            catch(RuntimeException unavailable){}
            boolean done=complete;try{store.transaction(item.owner(),()->{new SharedConnectRevocations(store).finish(claimed,done);return null;});}catch(RuntimeException failure){}
        }
    }
    void sweep(){
        if(closed||capacity.availablePermits()==0)return;
        revokeSharedGrants();
        try{
            var due=new ArrayList<Due>();Instant now=clock.now();
            try(var connection=datasource.getConnection();var held=restoreFence.enter(connection);
                    var query=connection.prepareStatement("SELECT r.owner_id,r.id,r.payload,c.payload FROM tk_resource r JOIN tk_resource c ON c.owner_id=r.owner_id AND c.id=r.id AND c.entity_type='TASK_CONNECTION' JOIN tk_account a ON a.owner_id=r.owner_id WHERE r.entity_type='TASK_AUTH' AND a.state<>'DISABLED'");var rows=query.executeQuery()){
                while(rows.next()){
                    var value=parse(rows.getString(3));var metadata=parse(rows.getString(4));
                    boolean selected=TaskProviderConnections.services(value).path("tasks").asBoolean()&&!value.path("selected").isEmpty()||TaskProviderConnections.services(value).path("calendars").asBoolean()&&!value.path("calendarSelected").isEmpty();
                    if(!metadata.path("enabled").asBoolean()||!metadata.path("readable").asBoolean()||value.path("paused").asBoolean()||value.path("blocked").asBoolean()||!selected&&!value.path("requested").asBoolean()
                            ||!automatic&&!value.path("requested").asBoolean()||!value.hasNonNull("nextRefresh"))continue;
                    var provider=TaskProviderConfiguration.provider(metadata.path("providerId").asText());if(!value.hasNonNull("brokerEndpoint")&&!configuration.available(provider))continue;
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
