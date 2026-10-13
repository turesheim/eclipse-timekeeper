package net.resheim.eclipse.timekeeper.nativeui.test;

import static org.junit.Assert.*;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.ui.client.EclipseBackend;

/** Verifies dispatch and acknowledgement semantics in an actual Eclipse Display and Job runtime. */
public class BackendJobTest {
    @Test public void waitingJobLeavesDisplayResponsiveAndCanBeCancelled() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),heartbeat=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicReference<Thread> worker=new AtomicReference<>();
        AtomicReference<org.eclipse.core.runtime.jobs.Job> job=new AtomicReference<>();
        try(var backend=backend(display)) {
            onUi(display,()->{
                job.set(backend.read("Wait for remote response",()->{
                    worker.set(Thread.currentThread());started.countDown();
                    try {release.await();return "response";}
                    catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new net.resheim.eclipse.timekeeper.client.RemoteFailure(net.resheim.eclipse.timekeeper.client.RemoteFailure.Kind.CANCELLED,"CANCELLED");}
                },value->fail("A cancelled operation must not report success"),failure->cancelled.countDown()));
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            onUi(display,()->display.timerExec(0,heartbeat::countDown));
            assertTrue("A pending request must not freeze SWT",heartbeat.await(2,TimeUnit.SECONDS));
            assertNotSame(display.getThread(),worker.get());
            job.get().cancel();
            assertTrue("Cancellation must release the Job and reach the UI",cancelled.await(3,TimeUnit.SECONDS));
        } finally {release.countDown();}
    }

    @Test public void acknowledgedSaveRemainsSuccessfulWhenRefreshFails() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        AtomicInteger writes=new AtomicInteger(),failures=new AtomicInteger();
        CountDownLatch acknowledged=new CountDownLatch(1);
        AtomicReference<String> saved=new AtomicReference<>();
        try(var backend=backend(display)) {
            onUi(display,()->backend.execute("Save acknowledged record",()->{
                writes.incrementAndGet();return "saved-record";
            },value->{saved.set(value);acknowledged.countDown();},failure->{failures.incrementAndGet();acknowledged.countDown();}));
            assertTrue(acknowledged.await(5,TimeUnit.SECONDS));
            assertEquals("saved-record",saved.get());
            assertEquals(1,writes.get());assertEquals(0,failures.get());
            assertFalse(backend.ready());
            assertTrue(backend.status().startsWith("The change was saved."));
        }
    }

    @Test public void unavailableRemoteConfigurationNeverInvokesEmbeddedBackend() throws Exception {
        Display display=PlatformUI.getWorkbench().getDisplay();
        AtomicInteger embeddedAccess=new AtomicInteger(),operations=new AtomicInteger();
        AtomicReference<RuntimeException> failure=new AtomicReference<>();
        try(var backend=EclipseBackend.unavailableRemote(()->{embeddedAccess.incrementAndGet();throw new AssertionError("Embedded fallback");},display,"Configure the remote backend.")) {
            assertTrue(backend.remote());assertFalse(backend.ready());assertFalse(backend.snapshotsAvailable());
            onUi(display,()->backend.execute("Save remote record",()->{operations.incrementAndGet();return backend.service().projects();},value->fail("Unavailable remote save"),failure::set));
            assertNotNull(failure.get());
            assertEquals("Configure the remote backend.",failure.get().getMessage());
            assertEquals(0,operations.get());assertEquals(0,embeddedAccess.get());
            try {backend.service().projects();fail("Unavailable remote read");}
            catch(IllegalStateException expected) {assertEquals("Configure the remote backend.",expected.getMessage());}
            assertEquals(0,embeddedAccess.get());
        }
    }

    private static EclipseBackend backend(Display display) {
        var client=new HttpTimekeeperService(URI.create("http://127.0.0.1:1"),()->"test-only-token",Duration.ofMillis(250));
        return new EclipseBackend(()->{throw new AssertionError("Embedded fallback");},client,display);
    }
    private static void onUi(Display display,Runnable action) throws Exception {
        FutureTask<Void> task=new FutureTask<>(action,null);
        display.asyncExec(task);task.get(5,TimeUnit.SECONDS);
    }
}
