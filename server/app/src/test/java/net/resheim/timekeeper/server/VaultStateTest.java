package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultStateTest.Profile.class)
class VaultStateTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_state_test",
                "timekeeper.notes.max-file-bytes","1000","timekeeper.notes.max-markdown-bytes","500",
                "timekeeper.notes.max-total-bytes","10000","timekeeper.notes.max-entries","8");}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void quotaFailureRollsBackCountersAndDoesNotExposeAnotherOwnersUsage(){
        store.transaction("a",()->{var state=new VaultState(store,limits);state.changed(9000,7,false);state.initialized();return null;});
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultState(store,limits).changed(1001,0,false)));
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultState(store,limits).changed(0,2,false)));
        store.transaction("a",()->{var value=new VaultState(store,limits).get();assertEquals(9000,value.path("totalBytes").asLong());assertEquals(7,value.path("entries").asInt());return null;});
        store.transaction("b",()->{var value=new VaultState(store,limits).get();assertEquals(0,value.path("totalBytes").asLong());assertEquals(0,value.path("entries").asInt());assertFalse(value.path("initialized").asBoolean());return null;});
    }
    @Test void historicalOveragesCanBeIndexedAndReducedWithoutTruncation(){
        store.transaction("historical",()->{var state=new VaultState(store,limits);state.changed(15000,12,true);state.initialized();state.changed(-1,-1,false);return null;});
        assertThrows(ApiFailure.class,()->store.transaction("historical",()->new VaultState(store,limits).changed(1,0,false)));
        store.transaction("historical",()->{var value=new VaultState(store,limits).get();assertEquals(14999,value.path("totalBytes").asLong());assertEquals(11,value.path("entries").asLong());return null;});
    }
    @Test void sameSizeEditsAndMovesInvalidateNamespacePreconditions(){
        store.transaction("generation",()->{
            var state=new VaultState(store,limits);state.initialized();String before=etag(state.get());var changed=state.changed(0,0,false);
            assertNotEquals(before,etag(changed));assertEquals(0,changed.path("totalBytes").asLong());assertEquals(0,changed.path("entries").asLong());return null;
        });
    }
}
