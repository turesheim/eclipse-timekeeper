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
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;

/** Real HTTP and Eclipse Jobs; the fixture models a restored server revision. */
public class RemoteChangeJobTest {
    @Test public void revisionResetReloadsWithoutOldEtagsAndCloseCancelsLongPoll() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        try(var server=new RestoredServer();
                var client=new HttpTimekeeperService(server.url(),()->"isolated-test-token",Duration.ofSeconds(15));
                var backend=new EclipseBackend(()->{throw new AssertionError("Embedded fallback");},client,display)) {
            await(backend::ready,"Initial remote snapshot");
            assertEquals(2,client.task(RestoredServer.ID).orElseThrow().version());
            assertTrue("Change watcher did not start: "+server.failure.get(),server.polling.await(5,TimeUnit.SECONDS));
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
            assertEquals("\"reset-task-1\"",server.putTag.get());assertEquals(1,server.writes.get());
            assertTrue(server.pollingAgain.await(5,TimeUnit.SECONDS));
            backend.close();
            assertTrue("Close must interrupt the outstanding long poll",watcher.join(3000,null));
            assertEquals(Job.NONE,watcher.getState());
            assertNull(server.failure.get());
        }
    }

    @Test public void sameVersionResetPreservesNativeDraftUntilReopenedAndReviewed() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        var plugin=TimekeeperUiPlugin.getDefault();
        var field=TimekeeperUiPlugin.class.getDeclaredField("backend");field.setAccessible(true);
        await(()->{
            try {return field.get(plugin)!=null;}catch(IllegalAccessException error) {throw new AssertionError(error);}
        },"Original isolated UI backend");
        var original=(EclipseBackend)field.get(plugin);AtomicReference<TitleAreaDialog> dialog=new AtomicReference<>();
        AtomicBoolean workweekWasOpen=new AtomicBoolean();
        onUi(display,()->{
            var page=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            workweekWasOpen.set(page.findView(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView.VIEW_ID)!=null);
            try {page.showView(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView.VIEW_ID);}catch(org.eclipse.ui.PartInitException error) {throw new AssertionError(error);}
            return true;
        });
        try(var server=new RestoredServer();
                var client=new HttpTimekeeperService(server.url(),()->"isolated-test-token",Duration.ofSeconds(15));
                var backend=new EclipseBackend(()->{throw new AssertionError("Embedded fallback");},client,display)) {
            await(backend::ready,"Initial dialog snapshot");
            assertEquals(2,client.task(RestoredServer.ID).orElseThrow().version());
            onUi(display,()->{try {field.set(plugin,backend);}catch(IllegalAccessException error) {throw new AssertionError(error);}return true;});
            openTaskDialog(display,backend,dialog);
            var bot=new SWTWorkbenchBot();var shell=bot.shell("Edit task");var controls=shell.bot();
            controls.textWithLabel("Summary").setText("Unreviewed old draft");
            assertTrue("Change watcher did not start: "+server.failure.get(),server.polling.await(5,TimeUnit.SECONDS));
            server.restore(2,"Changed fields after restore");
            await(()->onUi(display,()->backend.ready() && backend.service().task(RestoredServer.ID)
                    .filter(task->task.version()==2 && task.summary().equals("Changed fields after restore")).isPresent()),"Same-version restored snapshot");
            assertEquals("Unreviewed old draft",controls.textWithLabel("Summary").getText());
            controls.button("OK").click();
            await(()->server.writes.get()>0 || onUi(display,()->!shell.widget.isDisposed() && visibleMessage(shell.widget,
                    "This record has changed. Close and reopen the editor before saving.")),"Old editor must reject the changed same-version record");
            assertEquals("Unreviewed draft must not send an update after reset",0,server.writes.get());
            assertEquals("Changed fields after restore",server.summary);assertTrue(controls.button("OK").isEnabled());
            assertEquals("Unreviewed old draft",controls.textWithLabel("Summary").getText());controls.button("Cancel").click();
            openTaskDialog(display,backend,dialog);
            var reviewed=bot.shell("Edit task");assertEquals("Changed fields after restore",reviewed.bot().textWithLabel("Summary").getText());
            reviewed.bot().textWithLabel("Summary").setText("Explicitly reviewed save");reviewed.bot().button("OK").click();
            await(()->!reviewed.isOpen(),"Reviewed native editor did not save");
            assertEquals(1,server.writes.get());assertEquals("Explicitly reviewed save",server.summary);
            assertEquals("\"reset-task-2\"",server.putTag.get());assertNull(server.failure.get());
        } finally {
            onUi(display,()->{
                if(dialog.get()!=null && dialog.get().getShell()!=null && !dialog.get().getShell().isDisposed()) dialog.get().close();
                try {field.set(plugin,original);}catch(IllegalAccessException error) {throw new AssertionError(error);}
                var page=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                var view=(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView)page.findView(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView.VIEW_ID);
                if(view!=null) {if(workweekWasOpen.get()) view.refreshAll();else page.hideView(view);}
                return true;
            });
        }
    }

    private static void openTaskDialog(Display display,EclipseBackend backend,AtomicReference<TitleAreaDialog> dialog) {
        onUi(display,()->{
            try {
                var type=TimekeeperUiPlugin.getDefault().getBundle().loadClass("net.resheim.eclipse.timekeeper.ui.views.TaskDialog");
                var constructor=type.getDeclaredConstructor(org.eclipse.swt.widgets.Shell.class,
                        net.resheim.eclipse.timekeeper.service.TimekeeperService.class,net.resheim.eclipse.timekeeper.domain.Task.class,Optional.class,Optional.class);
                constructor.setAccessible(true);
                var form=(TitleAreaDialog)constructor.newInstance(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                        backend.service(),backend.service().task(RestoredServer.ID).orElseThrow(),Optional.empty(),Optional.empty());
                dialog.set(form);form.create();form.getShell().open();return true;
            } catch(Exception error) {throw new AssertionError(error);}
        });
    }
    private static boolean visibleMessage(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;
            if(child instanceof Composite composite && visibleMessage(composite,expected)) return true;
        }
        return false;
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
        volatile String summary="Restored task";
        RestoredServer() throws IOException {
            workers.submit(()->{
                while(!socket.isClosed()) {
                    try {var connection=socket.accept();workers.submit(()->serve(connection));}
                    catch(IOException error) {if(!socket.isClosed()) failure.compareAndSet(null,error);}
                }
            });
        }
        URI url() {return URI.create("http://127.0.0.1:"+socket.getLocalPort());}
        void restore() {restore(1,"Restored task");}
        void restore(int restoredVersion,String restoredSummary) {version=restoredVersion;revision=1;summary=restoredSummary;restored.countDown();}
        private String tag() {return "\""+(restored.getCount()==0 ? "reset-task-" : "task-")+version+"\"";}
        private String task() {return "{\"id\":\""+ID.value()+"\",\"version\":"+version+",\"projectId\":null,\"parentTaskId\":null,\"summary\":\""+summary+"\",\"url\":null,\"externalReferences\":[]}";}
        private void serve(Socket connection) {
            try(connection) {
                connection.setSoTimeout(5000);
                var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));
                String[] request=input.readLine().split(" ");Map<String,String> headers=new HashMap<>();String line;
                while(!(line=input.readLine()).isEmpty()) {int colon=line.indexOf(':');headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
                int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
                char[] characters=new char[length];int offset=0;
                while(offset<length) {int read=input.read(characters,offset,length-offset);if(read<0) throw new EOFException();offset+=read;}
                String requestBody=new String(characters);
                assertEquals("Bearer isolated-test-token",headers.get("authorization"));
                URI uri=URI.create(request[1]);String path=uri.getPath();int status=200;String body,etag=null;
                if(path.equals("/api/v1/changes")) {
                    long after=Long.parseLong(uri.getQuery().split("&")[0].substring("after=".length()));
                    if(restored.getCount()!=0) {polling.countDown();assertTrue(restored.await(10,TimeUnit.SECONDS));}
                    if(after>revision) {resets.incrementAndGet();status=409;body="{\"code\":\"RESYNC_REQUIRED\"}";}
                    else {pollingAgain.countDown();closed.await(10,TimeUnit.SECONDS);body="{\"items\":[],\"nextSequence\":"+after+",\"hasMore\":false}";}
                } else if(path.equals("/api/v1/me")) body="{\"ownerId\":\"fixture-owner\",\"zoneId\":\"UTC\",\"version\":0}";
                else if(path.equals("/api/v1/activities/active") || path.equals("/api/v1/work-sessions/active")) {status=204;body="";}
                // Other UI tests can leave Workweek open; its read-only report loads must
                // remain valid while this isolated backend is temporarily selected.
                else if(path.equals("/api/v1/activities")) body="{\"items\":[],\"revision\":"+revision+",\"nextCursor\":null}";
                else if(path.equals("/api/v1/reports/activities")) body="{\"revision\":"+revision+",\"generatedAt\":\"2026-10-13T00:00:00Z\",\"total\":\"PT0S\",\"days\":[]}";
                else if(path.equals("/api/v1/tasks/"+ID.value())) {
                    if(request[0].equals("PUT")) {
                        putTag.set(headers.get("if-match"));assertEquals(tag(),putTag.get());
                        // Fixture drafts are deliberately plain ASCII, without JSON escapes.
                        var name=java.util.regex.Pattern.compile("\"summary\":\"([^\"]*)\"").matcher(requestBody);assertTrue(name.find());
                        summary=name.group(1);writes.incrementAndGet();version++;revision++;
                    }
                    body=task();etag=tag();
                } else if(Set.of("/api/v1/tasks","/api/v1/projects","/api/v1/labels","/api/v1/work-sessions").contains(path))
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
