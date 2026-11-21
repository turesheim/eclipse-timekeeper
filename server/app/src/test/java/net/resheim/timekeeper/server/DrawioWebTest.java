package net.resheim.timekeeper.server;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawioWebTest {
    @Test
    void admitsOnlyAnExactHttpsOrigin() {
        assertNull(DrawioWeb.validateOrigin("NONE"));
        assertEquals("https://editor.example.org", DrawioWeb.validateOrigin("https://EDITOR.example.org:443"));
        assertEquals("https://editor.example.org:8443", DrawioWeb.validateOrigin("https://editor.example.org:8443"));
        for (String value : List.of("http://editor.example.org", "https://*.example.org",
                "https://editor.example.org/", "https://editor.example.org:", "https://editor.example.org:65536",
                "https://editor.example.org?plugin=example", "https://user@editor.example.org",
                "https://editor.example.org#frame", "https://editor.example.org; frame-src *")) {
            assertThrows(IllegalArgumentException.class, () -> DrawioWeb.validateOrigin(value), value);
        }
    }
}
