package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.eclipse.swt.SWT;

/** Operates the real GTK chooser in CI's private Xvfb display, without replacing SWT dialogs. */
public final class NativeGtkDialog {
    public static void choose(String title,Path path,Runnable open) throws Exception {interact(title,path.toAbsolutePath().toString(),open);}
    public static void saveFile(String title,Path path,String suffix,Runnable open) throws Exception {
        String location=path.toAbsolutePath().toString();
        // GTK adds the selected filter's suffix. Typing it in the location
        // entry as well produces .txt.txt in the tested SWT 3.135 runtime.
        assertTrue(location.endsWith(suffix));
        interact(title,location.substring(0,location.length()-suffix.length()),open);
    }
    public static void cancel(String title,Runnable open) throws Exception {interact(title,null,open);}
    private static void interact(String title,String path,Runnable open) throws Exception {
        assertEquals("Native chooser acceptance requires GTK","gtk",SWT.getPlatform());
        var worker=Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->{
                String id=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<deadline && id==null) {String found=run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim();if(!found.isEmpty()) id=found.lines().findFirst().orElseThrow();else Thread.sleep(100);}
                assertNotNull("Actual native chooser did not appear: "+title,id);
                try {
                run(true,"xdotool","windowfocus","--sync",id);
                // Mapping/focus can precede GTK's final focus assignment.
                Thread.sleep(250);
                // The matched chooser is focused above. Send Escape through the
                // keyboard so its release does not address a window already
                // destroyed by the press (X_SendEvent BadWindow in CI).
                if(path==null) run(true,"xdotool","key","Escape");
                else {
                    // GTK's Recent view does not accept a directory location.
                    // Home switches to normal browsing before entering the path.
                    run(true,"xdotool","key","--window",id,"alt+Home");
                    Thread.sleep(250);
                    run(true,"xdotool","key","--window",id,"ctrl+l");Thread.sleep(250);
                    run(true,"xdotool","key","--window",id,"ctrl+a");
                    run(true,"xdotool","type","--window",id,"--clearmodifiers","--delay","1",path);Thread.sleep(250);
                    // GTK location entry first navigates; a further Enter accepts the folder.
                    run(false,"xdotool","key","--window",id,"Return");Thread.sleep(750);
                    if(run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty()) return null;
                    for(int attempt=0;attempt<8;attempt++) {run(false,"xdotool","key","--window",id,"Return");Thread.sleep(250);if(run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty()) return null;}
                }
                assertTrue("Actual native chooser did not close: "+title,run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty());return null;
                } finally {
                    // Release a still-open matched chooser so a driver failure is
                    // reported instead of leaving SWTBot blocked until VM timeout.
                    if(!run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty())
                        run(false,"xdotool","key","--window",id,"Escape");
                }
            });
            open.run();result.get(30,TimeUnit.SECONDS);
        } finally {worker.shutdownNow();}
    }
    private static String run(boolean requireSuccess,String... command) throws Exception {
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        if(!process.waitFor(5,TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("Native chooser command timed out: "+command[1]);}
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(requireSuccess) assertEquals("Native chooser command failed: "+output,0,process.exitValue());return process.exitValue()==0 ? output : "";
    }
}
