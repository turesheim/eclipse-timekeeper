package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRestoreFenceTest {
    @TempDir Path directory;
    private String h2(){return "jdbc:h2:"+directory.resolve("fence");}
    private static void state(Connection c,String value){execute(c,"UPDATE tk_instance SET payload=? WHERE kind='CONTROL'",value);}
    private static InstanceRestoreFence initialized(Connection c) throws Exception {var fence=new InstanceRestoreFence();fence.initialize(c);return fence;}
    @Test void pendingAndChangedGenerationsRejectOldReplicasWithoutRefreshOrWrites() throws Exception {
        try(var c=DriverManager.getConnection(h2())) {
            ServerSchema.initializeOrValidate(c);var first=initialized(c);var second=initialized(c);
            state(c,object().put("restorePending",true).toString());
            assertEquals("INSTANCE_RESTORE_PENDING",assertThrows(ApiFailure.class,()->first.enter(c)).code);
            assertEquals("INSTANCE_RESTORE_PENDING",assertThrows(ApiFailure.class,()->initialized(c)).code);
            String generation=UUID.randomUUID().toString();state(c,object().put("restoreGeneration",generation).toString());
            for(var old:List.of(first,second)) {
                assertEquals("INSTANCE_RESTART_REQUIRED",assertThrows(ApiFailure.class,()->old.enter(c)).code);
                assertEquals("INSTANCE_RESTART_REQUIRED",assertThrows(ApiFailure.class,()->old.initialize(c)).code);
            }
            var restarted=initialized(c);try(var held=restarted.enter(c)){assertFalse(c.isClosed());}
            try(var rows=c.createStatement().executeQuery("SELECT COUNT(*) FROM tk_owner")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
    }
    @Test void malformedControlAndUninitializedGatesFailClosed() throws Exception {
        try(var c=DriverManager.getConnection(h2())) {
            ServerSchema.initializeOrValidate(c);assertEquals("INSTANCE_FENCE_UNAVAILABLE",assertThrows(ApiFailure.class,()->new InstanceRestoreFence().enter(c)).code);
            var fence=initialized(c);
            for(String value:List.of("{} trailing","[]","{\"restorePending\":true,\"restorePending\":false}","{\"restorePending\":\"false\"}","{\"restoreGeneration\":null}","{\"restoreGeneration\":\"0-0-0-0-1\"}")) {
                state(c,value);assertEquals("INSTANCE_FENCE_UNAVAILABLE",assertThrows(ApiFailure.class,()->fence.enter(c)).code);
            }
        }
    }
    @Test void callersCannotEstablishAnMvccSnapshotBeforeAcquiringTheGate() throws Exception {
        try(var c=DriverManager.getConnection(h2())) {
            ServerSchema.initializeOrValidate(c);var fence=initialized(c);c.setAutoCommit(false);
            assertThrows(SQLException.class,()->fence.enter(c));
        }
    }
    @Test void h2RefusesOtherSessionsWithoutRollingBackTheirWorkAndReleasesUncommittedRestore() throws Exception {
        String url=h2();InstanceRestoreFence old;
        try(var keeper=DriverManager.getConnection(url);var offline=DriverManager.getConnection(url)) {
            ServerSchema.initializeOrValidate(keeper);old=initialized(keeper);execute(keeper,"INSERT INTO tk_owner VALUES('bootstrap','UTC',0,0,?)","a".repeat(64));
            keeper.setAutoCommit(false);execute(keeper,"UPDATE tk_owner SET revision=1");
            assertEquals("INSTANCE_OTHER_DATABASE_CLIENTS",assertThrows(ApiFailure.class,()->InstanceRestoreFence.exclusive(offline)).code);
            keeper.commit();
        }
        try(var offline=DriverManager.getConnection(url)) {
            try(var exclusive=InstanceRestoreFence.exclusive(offline)) {
                assertThrows(SQLException.class,()->DriverManager.getConnection(url));
                offline.setAutoCommit(false);execute(offline,"UPDATE tk_owner SET revision=2");
            }
            try(var rows=offline.createStatement().executeQuery("SELECT revision FROM tk_owner")){assertTrue(rows.next());assertEquals(1,rows.getLong(1));}
            try(var exclusive=InstanceRestoreFence.exclusive(offline)){state(offline,object().put("restoreGeneration",UUID.randomUUID().toString()).toString());}
            assertEquals("INSTANCE_RESTART_REQUIRED",assertThrows(ApiFailure.class,()->old.enter(offline)).code);
            try(var held=initialized(offline).enter(offline)){assertFalse(offline.isClosed());}
        }
    }
    @Test void h2AutoServerRefusesASeparateJvmWithoutLosingItsTransaction() throws Exception {
        String url=h2()+";AUTO_SERVER=TRUE";Path ready=directory.resolve("peer.ready"),release=directory.resolve("peer.release"),log=directory.resolve("peer.log");InstanceRestoreFence old;
        try(var keeper=DriverManager.getConnection(url)) {
            ServerSchema.initializeOrValidate(keeper);old=initialized(keeper);execute(keeper,"INSERT INTO tk_owner VALUES('bootstrap','UTC',0,0,?)","a".repeat(64));
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var command=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceRestoreFencePeer.class.getName(),url,ready.toString(),release.toString());
            var process=command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!Files.exists(ready)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(Files.exists(ready),Files.readString(log));
                try(var offline=DriverManager.getConnection(url)){assertEquals("INSTANCE_OTHER_DATABASE_CLIENTS",assertThrows(ApiFailure.class,()->InstanceRestoreFence.exclusive(offline)).code);}
                Files.writeString(release,"release");assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));assertTrue(Files.readString(log).contains("FENCE_PEER_COMMITTED"));
            }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        }
        try(var offline=DriverManager.getConnection(url);var exclusive=InstanceRestoreFence.exclusive(offline)) {
            try(var rows=offline.createStatement().executeQuery("SELECT revision FROM tk_owner")){assertTrue(rows.next());assertEquals(7,rows.getLong(1));}
        }
    }
    @FunctionalInterface interface PgWork {void run(String url,String password,String owner) throws Exception;}
    static void postgres(PgWork work) throws Exception {
        String url=System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_URL"),password=System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD");
        org.junit.jupiter.api.Assumptions.assumeTrue(url!=null,"Requires dedicated PostgreSQL fixture");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"))throw new IllegalArgumentException("Only loopback disposable PostgreSQL is supported");
        String suffix=UUID.randomUUID().toString().replace("-","");String name="timekeeper_fence_"+suffix,owner="timekeeper_fence_owner_"+suffix;
        try(var admin=DriverManager.getConnection(url,"postgres",password)) {
            admin.createStatement().execute("CREATE ROLE "+owner+" NOLOGIN");
            try {
                admin.createStatement().execute("CREATE DATABASE "+name+" OWNER "+owner);
                try{work.run(url.substring(0,url.length()-8)+name,password,owner);}finally{admin.createStatement().execute("DROP DATABASE "+name+" WITH (FORCE)");}
            }finally{admin.createStatement().execute("DROP ROLE "+owner);}
        }
    }
    private static boolean lock(Connection c,String function) throws SQLException {try(var rows=c.createStatement().executeQuery("SELECT "+function+"("+InstanceRestoreFence.LOCK+")")){assertTrue(rows.next());return rows.getBoolean(1);}}
    @Test void postgresAllowsParallelReadersAndHoldsSessionLocksAcrossCommitUntilGuardClose() throws Exception {
        postgres((url,password,owner)->{
            try(var first=DriverManager.getConnection(url,"postgres",password);var second=DriverManager.getConnection(url,"postgres",password);var offline=DriverManager.getConnection(url,"postgres",password)) {
                ServerSchema.initializeOrValidate(first);var one=initialized(first);var two=initialized(second);
                var a=one.enter(first);var b=two.enter(second);
                try {
                    assertFalse(lock(offline,"pg_try_advisory_lock"));first.setAutoCommit(false);first.commit();assertFalse(lock(offline,"pg_try_advisory_lock"));
                    a.close();assertFalse(lock(offline,"pg_try_advisory_lock"));b.close();assertTrue(lock(offline,"pg_try_advisory_lock"));assertTrue(lock(offline,"pg_advisory_unlock"));
                }finally{a.close();b.close();}
            }
        });
    }
    @Test void postgresReplicaBootstrapValidationDoesNotQueueAnExclusiveLockBehindLiveReaders() throws Exception {
        postgres((url,password,owner)->{
            try(var runtime=DriverManager.getConnection(url,"postgres",password);var starting=DriverManager.getConnection(url,"postgres",password)) {
                ServerSchema.initializeOrValidate(runtime);var fence=initialized(runtime);var worker=Executors.newSingleThreadExecutor();
                try(var held=fence.enter(runtime)) {
                    var started=worker.submit(()->{ServerSchema.initializeOrValidate(starting);return true;});assertTrue(started.get(3,TimeUnit.SECONDS));
                    assertFalse(lock(starting,"pg_try_advisory_lock"));
                }finally{worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}
            }
        });
    }
    @Test void postgresTimedOutFenceAcquisitionDiscardsItsPhysicalSession() throws Exception {
        postgres((url,password,owner)->{
            try(var offline=DriverManager.getConnection(url,"postgres",password);var runtime=DriverManager.getConnection(url,"postgres",password)) {
                ServerSchema.initializeOrValidate(offline);var fence=initialized(runtime);assertTrue(lock(offline,"pg_try_advisory_lock"));var worker=Executors.newSingleThreadExecutor();
                try {
                    var failed=worker.submit(()->{try(var held=fence.enter(runtime)){return "UNSAFE";}catch(ApiFailure failure){return failure.code;}});
                    assertEquals("INSTANCE_MAINTENANCE_ACTIVE",failed.get(10,TimeUnit.SECONDS));assertTrue(runtime.isClosed());
                }finally{assertTrue(lock(offline,"pg_advisory_unlock"));worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}
            }
        });
    }
    @Test void postgresOfflineFenceNeedsNoSuperuserAndDoesNotTerminateOtherClients() throws Exception {
        postgres((url,password,owner)->{
            try(var offline=DriverManager.getConnection(url,"postgres",password)) {
                execute(offline,"SET ROLE "+owner);
                try(var rows=offline.createStatement().executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname=current_user")){assertTrue(rows.next());assertFalse(rows.getBoolean(1));}
                ServerSchema.initializeOrValidate(offline);
                try(var peer=DriverManager.getConnection(url,"postgres",password)) {
                    assertEquals("INSTANCE_OTHER_DATABASE_CLIENTS",assertThrows(ApiFailure.class,()->InstanceRestoreFence.exclusive(offline)).code);
                    try(var rows=peer.createStatement().executeQuery("SELECT 1")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                }
                InstanceRestoreFence.Exclusive held=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(held==null){try{held=InstanceRestoreFence.exclusive(offline);}catch(ApiFailure draining){if(System.nanoTime()>deadline)throw draining;Thread.sleep(10);}}
                try(var exclusive=held){offline.setAutoCommit(false);execute(offline,"INSERT INTO tk_owner VALUES('uncommitted','UTC',0,0,?)","a".repeat(64));}
                try(var rows=offline.createStatement().executeQuery("SELECT COUNT(*) FROM tk_owner")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            }
        });
    }
    @Test void postgresWaitingRepeatableReadRequestsObserveTheNewGenerationAfterExclusiveRelease() throws Exception {
        postgres((url,password,owner)->{
            try(var offline=DriverManager.getConnection(url,"postgres",password);var runtime=DriverManager.getConnection(url,"postgres",password)) {
                ServerSchema.initializeOrValidate(offline);var old=initialized(runtime);runtime.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertTrue(lock(offline,"pg_try_advisory_lock"));var entered=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
                try {
                    var request=worker.submit(()->{entered.countDown();try(var held=old.enter(runtime)){return "UNSAFE";}catch(ApiFailure failure){return failure.code;}});
                    assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->request.get(100,TimeUnit.MILLISECONDS));
                    state(offline,object().put("restoreGeneration",UUID.randomUUID().toString()).toString());assertTrue(lock(offline,"pg_advisory_unlock"));
                    assertEquals("INSTANCE_RESTART_REQUIRED",request.get(5,TimeUnit.SECONDS));
                    assertTrue(lock(offline,"pg_try_advisory_lock"));assertTrue(lock(offline,"pg_advisory_unlock"));
                }finally{worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}
            }
        });
    }
}
