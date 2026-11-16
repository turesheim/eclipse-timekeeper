package net.resheim.timekeeper.server;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.runtime.Startup;

/** Make an old explicit heap override visible without preventing recovery or diagnostics. */
@Startup @ApplicationScoped
class RuntimeMemory {
    static final long MINIMUM_HEAP=2L*1024*1024*1024;
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(RuntimeMemory.class);
    @PostConstruct void report(){
        long maximum=Runtime.getRuntime().maxMemory();
        if(maximum<MINIMUM_HEAP)LOG.warnf("JVM heap below supported minimum max_heap_bytes=%d required_heap_bytes=%d; configure at least -Xmx2g and allow additional RAM outside the heap",maximum,MINIMUM_HEAP);
        else LOG.infof("JVM heap max_heap_bytes=%d required_heap_bytes=%d",maximum,MINIMUM_HEAP);
    }
}
