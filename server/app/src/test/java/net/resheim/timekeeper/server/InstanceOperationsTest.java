package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.InstanceOperations.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceOperationsTest {
    @TempDir Path directory;
    private static final Instant NOW=Instant.parse("2026-10-28T08:00:00Z");
    private String url(){return "jdbc:h2:"+directory.resolve("operations")+";LOCK_TIMEOUT=10000";}
    private Connection open() throws Exception {return DriverManager.getConnection(url());}
    private void initialize() throws Exception {try(var connection=open()){ServerSchema.initializeOrValidate(connection);}}
    @Test void interruptedWorkerCannotPublishAndAuditSurvivesDeletedActor() throws Exception {
        initialize();Lease abandoned;
        try(var c=open()){c.setAutoCommit(false);abandoned=begin(c,"bootstrap-owner",Type.BACKUP,NOW);c.commit();}
        try(var c=open()) {
            c.setAutoCommit(false);assertEquals(1,interruptAbandoned(c,NOW.plusSeconds(601)));
            assertEquals(State.INTERRUPTED,status(c,abandoned.id()).state());
            var next=begin(c,"new-administrator",Type.BACKUP,NOW.plusSeconds(601));
            assertEquals("INSTANCE_OPERATION_LEASE_LOST",assertThrows(ApiFailure.class,()->advance(c,abandoned,State.PREPARING,State.READY,NOW.plusSeconds(602))).code);
            advance(c,next,State.PREPARING,State.READY,NOW.plusSeconds(602));c.commit();
        }
        // No owner FK: deleting/replacing the target's setup account cannot
        // erase its operation receipts or the identity that authorized them.
        try(var c=open();var q=c.createStatement();var rows=q.executeQuery("SELECT actor_owner,payload FROM tk_instance WHERE kind='AUDIT' ORDER BY occurred_at,id")) {
            var states=new HashSet<String>();int count=0;
            while(rows.next()){count++;states.add(rows.getString(2));assertTrue(Set.of("bootstrap-owner","new-administrator").contains(rows.getString(1)));}
            assertEquals(4,count);assertEquals(3,states.size());
        }
    }
    @Test void concurrentAdministratorsCannotStartTwoActiveOperations() throws Exception {
        initialize();var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<String>>();
            for(int index=0;index<2;index++){final String actor="administrator-"+index;results.add(workers.submit(()->{
                try(var c=open()){c.setAutoCommit(false);ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));
                    try{begin(c,actor,Type.RESTORE,NOW);c.commit();return "STARTED";}
                    catch(ApiFailure failure){c.rollback();return failure.code;}
                }
            }));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var outcomes=new HashSet<String>();for(var result:results)outcomes.add(result.get(15,TimeUnit.SECONDS));
            assertEquals(Set.of("STARTED","INSTANCE_OPERATION_IN_PROGRESS"),outcomes);
            try(var c=open();var q=c.createStatement();var rows=q.executeQuery("SELECT count(*) FROM tk_instance WHERE kind='AUDIT'")){rows.next();assertEquals(1,rows.getInt(1));}
        }finally{go.countDown();workers.shutdownNow();}
    }
    @Test void restoreReviewReleasesWorkerButRequiresExplicitDiscardBeforeAnotherOperation() throws Exception {
        initialize();UUID id;
        try(var c=open()) {
            c.setAutoCommit(false);var lease=begin(c,"administrator",Type.RESTORE,NOW);id=lease.id();
            assertEquals("INSTANCE_OPERATION_STATE",assertThrows(ApiFailure.class,()->advance(c,lease,State.UPLOADING,State.READY,NOW)).code);
            advance(c,lease,State.UPLOADING,State.VALIDATING,NOW.plusSeconds(1));
            var review=advance(c,lease,State.VALIDATING,State.REVIEW,NOW.plusSeconds(2));assertEquals(3,review.version());c.commit();
        }
        try(var c=open()) {
            c.setAutoCommit(false);assertEquals(0,interruptAbandoned(c,NOW.plusSeconds(1200)));
            assertEquals("INSTANCE_OPERATION_IN_PROGRESS",assertThrows(ApiFailure.class,()->begin(c,"other",Type.BACKUP,NOW.plusSeconds(1200))).code);
            expire(c,id,"other-administrator",NOW.plusSeconds(1201));begin(c,"other",Type.BACKUP,NOW.plusSeconds(1202));c.commit();
        }
    }
    @Test void leaseRenewalKeepsLongOperationAliveButCannotReviveExpiredWorker() throws Exception {
        initialize();try(var c=open()) {
            c.setAutoCommit(false);var lease=begin(c,"administrator",Type.BACKUP,NOW);
            renew(c,lease,State.PREPARING,NOW.plusSeconds(500));assertEquals(0,interruptAbandoned(c,NOW.plusSeconds(601)));
            assertEquals("INSTANCE_OPERATION_LEASE_LOST",assertThrows(ApiFailure.class,()->renew(c,new Lease(lease.id(),UUID.randomUUID()),State.PREPARING,NOW.plusSeconds(602))).code);
            assertEquals("INSTANCE_OPERATION_LEASE_LOST",assertThrows(ApiFailure.class,()->renew(c,lease,State.PREPARING,NOW.plusSeconds(1100))).code);
            assertEquals(1,interruptAbandoned(c,NOW.plusSeconds(1100)));c.commit();
        }
    }
    @Test void differentReplicaMountCannotSilentlyBindTheSameDatabase() throws Exception {
        initialize();var original=new InstanceArtifacts(directory.resolve("mount-a"),8,16,2);
        var different=new InstanceArtifacts(directory.resolve("mount-b"),8,16,2);UUID root=original.identity();
        try(var c=open()){c.setAutoCommit(false);bindStorage(c,root);c.commit();}
        try(var c=open()) {
            c.setAutoCommit(false);bindStorage(c,root);
            assertEquals("INSTANCE_STORAGE_MISMATCH",assertThrows(ApiFailure.class,()->bindStorage(c,different.identity())).code);c.rollback();
            bindStorage(c,root);c.commit();
        }
    }
    @Test void databaseRollbackCannotLeaveAnAuditClaimingAnUncommittedOperation() throws Exception {
        initialize();UUID id;try(var c=open()){c.setAutoCommit(false);id=begin(c,"administrator",Type.BACKUP,NOW).id();c.rollback();}
        try(var c=open();var q=c.createStatement();var rows=q.executeQuery("SELECT count(*) FROM tk_instance WHERE kind<>'CONTROL'")){rows.next();assertEquals(0,rows.getInt(1));assertEquals(404,assertThrows(ApiFailure.class,()->status(c,id)).status);}
    }
}
