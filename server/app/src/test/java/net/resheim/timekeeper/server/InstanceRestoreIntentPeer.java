package net.resheim.timekeeper.server;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Separate-process private-intent lock probe; synthetic files only. */
public final class InstanceRestoreIntentPeer {
    public static void main(String[] args) throws Exception {
        try(var prepared=new InstanceRestoreIntent(Path.of(args[0]),10_000_000).open()) {
            Files.writeString(Path.of(args[1]),"ready");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(Path.of(args[2]))){if(System.nanoTime()>deadline)throw new IllegalStateException("Intent peer timed out");Thread.sleep(10);}
            Runtime.getRuntime().halt(0);
        }
    }
}
