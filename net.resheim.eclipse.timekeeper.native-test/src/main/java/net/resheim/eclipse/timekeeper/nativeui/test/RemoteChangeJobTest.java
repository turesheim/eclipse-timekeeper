package net.resheim.eclipse.timekeeper.nativeui.test;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.ui.client.EclipseBackend;

/** Real HTTP and Eclipse Jobs; the fixture models a restored server revision. */
public class RemoteChangeJobTest {
    @Test public void revisionResetReloadsWithoutOldEtagsAndCloseCancelsLongPoll() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        try(var server=new RestoredServer();
                var client=new HttpTimekeeperService(server.url(),()->"isolated-test-token",Duration.ofSeconds(15));
                var backend=new EclipseBackend(()->{throw new AssertionError("Embedded fallback");},client,display)) {
            await(backend::ready,"Initial remote snapshot");
            assertEquals(2,client.task(RestoredServer.ID).orElseThrow().version());
            assertTrue(server.polling.await(5,TimeUnit.SECONDS));
            var watcher=Arrays.stream(Job.getJobManager().find(null))
                    .filter(job->job.getName().equals("Watch Timekeeper changes")).findFirst().orElseThrow();
            CountDownLatch heartbeat=new CountDownLatch(1);display.asyncExec(heartbeat::countDown);
            assertTrue("Long polling must leave the Display responsive",heartbeat.await(2,TimeUnit.SECONDS));
            server.restore();
            await(()->onUi(display,()->backend.ready() && backend.service().task(RestoredServer.ID)
                    .filter(task->task.version()==1).isPresent()),"Restored snapshot without manual refresh");
            assertEquals(1,server.resets.get());
            CountDownLatch saved=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            backend.execute("Save reviewed restored task",()->backend.service().updateTask(new UpdateTask(
                    RestoredServer.ID,1,Optional.empty(),Optional.empty(),"Reviewed restored task",Optional.empty())),
                    value->saved.countDown(),error->{failure.set(error);saved.countDown();});
            assertTrue(saved.await(5,TimeUnit.SECONDS));assertNull(failure.get());
            assertEquals("\"task-1\"",server.putTag.get());assertEquals(1,server.writes.get());
            assertTrue(server.pollingAgain.await(5,TimeUnit.SECONDS));
            backend.close();
            assertTrue("Close must interrupt the outstanding long poll",watcher.join(3000,null));
            assertEquals(Job.NONE,watcher.getState());
            assertNull(server.failure.get());
        }
    }

    private static boolean onUi(Display display,BooleanSupplier action) {
        FutureTask<Boolean> task=new FutureTask<>(action::getAsBoolean);display.asyncExec(task);
        try {return task.get(3,TimeUnit.SECONDS);}catch(Exception error) {throw new AssertionError(error);}
    }
    private static void await(BooleanSupplier condition,String description) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(25);
        assertTrue(description,condition.getAsBoolean());
    }

    private static final class RestoredServer implements AutoCloseable {
        static final TaskId ID=new TaskId(UUID.fromString("77777777-7777-4777-8777-777777777777"));
        final CountDownLatch polling=new CountDownLatch(1),restored=new CountDownLatch(1),pollingAgain=new CountDownLatch(1),closed=new CountDownLatch(1);
        final AtomicInteger resets=new AtomicInteger(),writes=new AtomicInteger();
        final AtomicReference<String> putTag=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final ServerSocket socket=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
        volatile int revision=2,version=2;
        RestoredServer() throws IOException {
            workers.submit(()->{
                while(!socket.isClosed()) {
                    try {var connection=socket.accept();workers.submit(()->serve(connection));}
                    catch(IOException error) {if(!socket.isClosed()) failure.compareAndSet(null,error);}
                }
            });
        }
        URI url() {return URI.create("http://127.0.0.1:"+socket.getLocalPort());}
        void restore() {version=1;revision=1;restored.countDown();}
        private String task() {return "{\"id\":\""+ID.value()+"\",\"version\":"+version+",\"projectId\":null,\"parentTaskId\":null,\"summary\":\"Restored task\",\"url\":null,\"externalReferences\":[]}";}
        private void serve(Socket connection) {
            try(connection) {
                connection.setSoTimeout(5000);
                var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));
                String[] request=input.readLine().split(" ");Map<String,String> headers=new HashMap<>();String line;
                while(!(line=input.readLine()).isEmpty()) {int colon=line.indexOf(':');headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
                int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
                while(length>0) {int read=input.read(new char[length]);if(read<0) throw new EOFException();length-=read;}
                assertEquals("Bearer isolated-test-token",headers.get("authorization"));
                URI uri=URI.create(request[1]);String path=uri.getPath();int status=200;String body,etag=null;
                if(path.equals("/api/v1/changes")) {
                    long after=Long.parseLong(uri.getQuery().split("&")[0].substring("after=".length()));
                    if(restored.getCount()!=0) {polling.countDown();assertTrue(restored.await(10,TimeUnit.SECONDS));}
                    if(after>revision) {resets.incrementAndGet();status=409;body="{\"code\":\"RESYNC_REQUIRED\"}";}
                    else {pollingAgain.countDown();closed.await(10,TimeUnit.SECONDS);body="{\"items\":[],\"nextSequence\":"+after+",\"hasMore\":false}";}
                } else if(path.equals("/api/v1/me")) body="{\"ownerId\":\"fixture-owner\",\"zoneId\":\"UTC\",\"version\":0}";
                else if(path.equals("/api/v1/activities/active")) {status=204;body="";}
                else if(path.equals("/api/v1/tasks/"+ID.value())) {
                    if(request[0].equals("PUT")) {
                        putTag.set(headers.get("if-match"));assertEquals("\"task-1\"",putTag.get());writes.incrementAndGet();version++;revision++;
                    }
                    body=task();etag="\"task-"+version+"\"";
                } else if(Set.of("/api/v1/tasks","/api/v1/projects","/api/v1/labels").contains(path))
                    body="{\"items\":["+(path.endsWith("/tasks") ? task() : "")+"],\"revision\":"+revision+",\"nextCursor\":null}";
                else throw new AssertionError("Unexpected fixture route: "+path);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                String response="HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+(etag==null ? "" : "ETag: "+etag+"\r\n")+"\r\n";
                var output=connection.getOutputStream();output.write(response.getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
            } catch(IOException disconnected) {if(closed.getCount()!=0) failure.compareAndSet(null,disconnected);}
            catch(Throwable error) {if(closed.getCount()!=0) failure.compareAndSet(null,error);}
        }
        @Override public void close() throws IOException {closed.countDown();restored.countDown();socket.close();workers.shutdownNow();}
    }
}
