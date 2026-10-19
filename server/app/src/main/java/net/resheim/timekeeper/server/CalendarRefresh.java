package net.resheim.timekeeper.server;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Short owner-locked claims, bounded I/O outside JDBC transactions, and cross-replica leases. */
@ApplicationScoped
class CalendarRefresh {
    @Inject ServerStore store;
    @Inject AgroalDataSource datasource;
    @Inject BackendClock clock;
    @ConfigProperty(name="timekeeper.calendars.trusted-origins") Optional<String> trustedOrigins;
    @ConfigProperty(name="timekeeper.calendars.automatic",defaultValue="true") boolean automatic;
    private CalendarDownload download;
    private final Semaphore capacity=new Semaphore(4);
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"calendar-refresh");thread.setDaemon(true);return thread;});
    private volatile boolean closed;
    @PostConstruct void initialize(){download=new CalendarDownload(trustedOrigins.orElse(""));}
    CalendarDownload policy(){return download;}
    void start(@Observes StartupEvent event){if(automatic)scheduler.scheduleWithFixedDelay(this::sweep,1,60,TimeUnit.SECONDS);}
    @PreDestroy void close(){closed=true;scheduler.shutdownNow();workers.shutdownNow();}
    void wake(){if(automatic&&!closed)try{scheduler.execute(this::sweep);}catch(RejectedExecutionException ignored){}}
    private void fetch(CalendarSubscriptions.Claim claim,RequestIdentity identity){
        CalendarDownload.Result result=null;String error=null;
        try{
            result=download.get(claim.url(),claim.etag(),claim.modified(),claim.body()!=null);
            if(!result.unchanged())new CalendarFeed(result.body());
        }catch(CalendarFailure failure){error=failure.code;}
        catch(Exception failure){error="CALENDAR_CONNECTION_FAILED";}
        var outcome=result;String failure=error;
        if(identity==null)store.transaction(claim.owner(),()->{new CalendarSubscriptions(store).finish(claim,outcome,failure);return null;});
        else store.authenticated(identity,()->{new CalendarSubscriptions(store).finish(claim,outcome,failure);return null;});
    }
    ApiReplies refresh(RequestIdentity identity,UUID id){
        if(!capacity.tryAcquire())throw new ApiFailure(503,"CALENDAR_BUSY");
        try{
            var claim=store.authenticated(identity,()->new CalendarSubscriptions(store).claim(id,true));
            if(claim!=null)fetch(claim,identity);
            return store.authenticated(identity,()->{var calendars=new CalendarSubscriptions(store);return ApiReplies.resource(claim==null?202:200,calendars.visible(calendars.required(id)),null);});
        }finally{capacity.release();}
    }
    private record Due(String owner,UUID id,Instant next){}
    void sweep(){
        if(closed||capacity.availablePermits()==0)return;
        try{
            var due=new ArrayList<Due>();Instant now=clock.now();
            try(var connection=datasource.getConnection();var query=connection.prepareStatement("SELECT r.owner_id,r.id,r.payload FROM tk_resource r JOIN tk_account a ON a.owner_id=r.owner_id WHERE r.entity_type='CAL_SUB' AND a.state<>'DISABLED'");var rows=query.executeQuery()){
                while(rows.next()){
                    var value=JsonValues.parse(rows.getString(3));Instant next=Instant.parse(value.path("nextRefresh").asText());
                    if(!next.isAfter(now))due.add(new Due(rows.getString(1),rows.getObject(2,UUID.class),next));
                }
            }
            due.sort(Comparator.comparing(Due::next).thenComparing(Due::owner).thenComparing(Due::id));
            for(var candidate:due){
                if(closed||!capacity.tryAcquire())break;
                CalendarSubscriptions.Claim claim;
                try{claim=store.transaction(candidate.owner(),()->new CalendarSubscriptions(store).claim(candidate.id(),false));}
                catch(RuntimeException failure){capacity.release();continue;}
                if(claim==null){capacity.release();continue;}
                try{workers.submit(()->{try{fetch(claim,null);}catch(RuntimeException failure){/* The lease expires; the next sweep retries without publishing an incomplete feed. */}finally{capacity.release();wake();}});}
                catch(RejectedExecutionException failure){capacity.release();}
            }
        }catch(SQLException|RuntimeException failure){/* A later sweep retries; no source credentials enter the logs. */}
    }
}
