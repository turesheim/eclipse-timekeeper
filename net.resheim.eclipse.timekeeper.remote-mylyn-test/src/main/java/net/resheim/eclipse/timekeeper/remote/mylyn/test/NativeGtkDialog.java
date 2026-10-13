package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.eclipse.swt.SWT;

/** Operates the real GTK chooser in CI's private Xvfb display, without replacing SWT dialogs. */
final class NativeGtkDialog {
    static void choose(String title,Path path,Runnable open) throws Exception {interact(title,path.toAbsolutePath().toString(),open);}
    static void saveFile(String title,Path path,String suffix,Runnable open) throws Exception {
        String location=path.toAbsolutePath().toString();
        // GTK adds the selected filter's suffix. Typing it in the location
        // entry as well produces .txt.txt in the tested SWT 3.135 runtime.
        assertTrue(location.endsWith(suffix));
        interact(title,location.substring(0,location.length()-suffix.length()),open);
    }
    static void cancel(String title,Runnable open) throws Exception {interact(title,null,open);}
    private static void interact(String title,String path,Runnable open) throws Exception {
        assertEquals("Native chooser acceptance requires GTK","gtk",SWT.getPlatform());
        var worker=Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->{
                String id=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<deadline && id==null) {String found=run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim();if(!found.isEmpty()) id=found.lines().findFirst().orElseThrow();else Thread.sleep(100);}
                assertNotNull("Actual native chooser did not appear: "+title,id);
                run(true,"xdotool","windowfocus","--sync",id);
                if(path==null) run(true,"xdotool","key","--window",id,"Escape");
                else {
                    run(true,"xdotool","key","--window",id,"ctrl+l");run(true,"xdotool","type","--window",id,"--clearmodifiers","--delay","1",path);
                    // GTK location entry first navigates; a further Enter accepts the folder.
                    for(int attempt=0;attempt<8;attempt++) {run(false,"xdotool","key","--window",id,"Return");Thread.sleep(250);if(run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty()) return null;}
                }
                assertTrue("Actual native chooser did not close: "+title,run(false,"xdotool","search","--onlyvisible","--name","^"+title+"$").trim().isEmpty());return null;
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
