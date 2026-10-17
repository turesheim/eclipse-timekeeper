package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HostPolicyTest {
    @Test void onlyExactEffectiveHostsAndLoopbackAreAcceptedRegardlessOfDnsCaseOrForwardedPorts() {
        for(String value:new String[]{"localhost","localhost:8080","127.0.0.1:18081","[::1]:8080","timekeeper.example","TIMEKEEPER.EXAMPLE:8443"})
            assertTrue(HostPolicy.allowed(value,"https://timekeeper.example:8443"),value);
        assertTrue(HostPolicy.allowed("[2001:db8::1]:8080","https://[2001:db8::1]:8443"));
        assertTrue(HostPolicy.allowed("192.0.2.1:8080","https://192.0.2.1:8443"));
        assertFalse(HostPolicy.allowed("timekeeper.example","NONE"));
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={" "," timekeeper.example","timekeeper.example ","foreign.example","localhost.foreign.example","127.0.0.1.foreign.example","timekeeper.example.foreign.example","foreign@timekeeper.example","timekeeper.example@foreign","timekeeper.example/path","timekeeper.example?query","timekeeper.example#fragment","timekeeper.example:0","timekeeper.example:65536","timekeeper.example:8080:80","timekeeper.example,foreign.example"})
    void malformedAndForeignAuthoritiesCannotBorrowTheConfiguredHost(String authority) {
        assertFalse(HostPolicy.allowed(authority,"https://timekeeper.example"));
    }
}
