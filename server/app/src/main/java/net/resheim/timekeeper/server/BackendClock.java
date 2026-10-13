package net.resheim.timekeeper.server;

import java.time.Instant;
import jakarta.enterprise.context.ApplicationScoped;

/** One backend clock, replaceable in tests without exposing a production override. */
@ApplicationScoped
public class BackendClock {
    public Instant now() {return Instant.now();}
}
