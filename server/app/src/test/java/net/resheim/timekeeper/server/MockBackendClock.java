package net.resheim.timekeeper.server;

import java.time.Instant;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

@Mock @ApplicationScoped
public class MockBackendClock extends BackendClock {
    private volatile Instant fixed;
    public void set(Instant value) {fixed=value;}
    @Override public Instant now() {var value=fixed;return value==null ? Instant.now() : value;}
}
