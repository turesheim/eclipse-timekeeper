package net.resheim.timekeeper.server;

import java.util.*;
import java.util.concurrent.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Bounded, restartable enrollment of existing Markdown; canonical writes reconcile immediately. */
@ApplicationScoped
class NoteTaskRefresh {
    @Inject ServerStore store;
    @Inject AgroalDataSource datasource;
    @Inject InstanceRestoreFence restoreFence;
    @ConfigProperty(name="timekeeper.notes.tasks.automatic",defaultValue="true") boolean automatic;
    private record Candidate(String owner,UUID id){}
    private final Map<String,Candidate> cursors=new HashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(run->{var thread=new Thread(run,"note-task-refresh");thread.setDaemon(true);return thread;});
    void start(@Observes StartupEvent event){if(automatic)worker.scheduleWithFixedDelay(this::sweep,5,30,TimeUnit.SECONDS);}
    @PreDestroy void close(){worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException failure){Thread.currentThread().interrupt();}}
    synchronized void sweep(){
        for(String type:List.of(VaultContent.TYPE,DayNotes.TYPE,WeekNotes.TYPE,EventNotes.TYPE)){
            var after=cursors.get(type);var candidates=new ArrayList<Candidate>();
            try(var connection=datasource.getConnection();var held=restoreFence.enter(connection);var query=connection.prepareStatement(
                    "SELECT r.owner_id,r.id FROM tk_resource r LEFT JOIN tk_account a ON a.owner_id=r.owner_id WHERE r.entity_type=? AND (a.state IS NULL OR a.state<>'DISABLED')"+
                    (after==null?"":" AND (r.owner_id>? OR (r.owner_id=? AND r.id>?))")+" ORDER BY r.owner_id,r.id FETCH FIRST 25 ROWS ONLY")){
                query.setString(1,type);if(after!=null){query.setString(2,after.owner());query.setString(3,after.owner());query.setObject(4,after.id());}
                try(var rows=query.executeQuery()){while(rows.next())candidates.add(new Candidate(rows.getString(1),rows.getObject(2,UUID.class)));}
            }catch(Exception failure){continue;}
            for(var candidate:candidates)try{store.transaction(candidate.owner(),()->{new NoteTasks(store).existing(type,candidate.id());return null;});}
            catch(RuntimeException failure){org.jboss.logging.Logger.getLogger(NoteTaskRefresh.class).warn("Note task reconciliation deferred; source and task changes rolled back");}
            if(candidates.size()<25)cursors.remove(type);else cursors.put(type,candidates.getLast());
        }
    }
}
