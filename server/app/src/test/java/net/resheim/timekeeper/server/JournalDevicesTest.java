package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(JournalImportTest.Profile.class)
class JournalDevicesTest {
    @Inject ServerStore store;
    private static final String PASSWORD_HASH=AccountPasswords.hash("Journal device fixture passphrase");
    @BeforeEach void reset(){
        io.quarkus.arc.ClientProxy.unwrap(store).stopMaintenance();
        for(String owner:List.of("a","b"))store.transaction(owner,()->{
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND (entity_type LIKE 'JOURNAL_%' OR entity_type='DAY_NOTE')",owner);
            store.update("UPDATE tk_account SET state='ENABLED',username=?,password_hash=? WHERE owner_id=?","journal-"+owner,PASSWORD_HASH,owner);return null;
        });
    }
    private ObjectNode enroll()throws Exception{String key=JournalKeyTest.publicKey();return store.transaction("a",()->new JournalDevices(store).enroll("Laptop",key,null));}
    @Test void settingsStartDisabledAndDeviceKeysArePublicOnly()throws Exception{
        var device=enroll();UUID id=uuid(device.get("id"));assertTrue(device.path("fingerprint").asText().startsWith("SHA256:"));assertFalse(device.has("publicKey"));
        assertEquals("JOURNAL_DISABLED",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).authorize(id))).code);
        store.transaction("a",()->{var devices=new JournalDevices(store);devices.enabled(true);assertFalse(devices.authorize(id).path("revoked").asBoolean());assertEquals(1,devices.list().size());return null;});
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("b",()->new JournalDevices(store).revoke(id))).status);
        store.transaction("a",()->{var audits=new JournalState(store).list(JOURNAL_AUDIT);assertEquals(2,audits.size());assertTrue(audits.stream().noneMatch(audit->audit.has("publicKey")||audit.has("label")||audit.has("source")));return null;});
    }
    @Test void revocationAndDisableAbortLeasesAndPendingImportsWhileRetainingRecoveryCopies()throws Exception{
        UUID id=uuid(enroll().get("id"));UUID lease=UUID.randomUUID(),batch=UUID.randomUUID();
        store.transaction("a",()->{
            new JournalDevices(store).enabled(true);new JournalState(store).insert(JOURNAL_LEASE,lease,object().put("deviceId",id.toString()).put("state","RECEIVING"));
            new JournalImport(store).seal(batch,id,1,Map.of("2026-10-25.md",new byte[0]));new JournalDevices(store).revoke(id);
            assertEquals("ABORTED",new JournalState(store).required(JOURNAL_LEASE,lease).path("state").asText());
            var item=new JournalState(store).list(JOURNAL_ITEM).getFirst();assertEquals("ABORTED",item.path("state").asText());assertTrue(item.has("source"));assertTrue(new JournalImport(store).pending().isEmpty());return null;
        });
        assertEquals("JOURNAL_DEVICE_REVOKED",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).authorize(id))).code);
        UUID other=uuid(enroll().get("id"));store.transaction("a",()->{var devices=new JournalDevices(store);long epoch=devices.settings().path("epoch").asLong();devices.enabled(false);devices.enabled(true);assertEquals(epoch+2,devices.settings().path("epoch").asLong());return null;});
        store.transaction("a",()->{store.update("UPDATE tk_account SET state='DISABLED' WHERE owner_id=?",store.owner());return null;});
        assertEquals("JOURNAL_ACCOUNT_DISABLED",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).authorize(other))).code);
    }
    @Test void rotationIsAtomicAndDoesNotAllowDuplicateActiveKeys()throws Exception{
        UUID old=uuid(enroll().get("id"));String key=JournalKeyTest.publicKey();
        assertThrows(IllegalStateException.class,()->store.transaction("a",()->{new JournalDevices(store).enroll("Replacement",key,old);throw new IllegalStateException("transaction rollback");}));
        store.transaction("a",()->{var devices=new JournalDevices(store);devices.enabled(true);assertFalse(devices.authorize(old).path("revoked").asBoolean());assertEquals(1,devices.list().size());return null;});
        var replacement=store.transaction("a",()->new JournalDevices(store).enroll("Replacement",key,old));UUID current=uuid(replacement.get("id"));
        assertEquals("JOURNAL_DEVICE_REVOKED",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).authorize(old))).code);
        store.transaction("a",()->{assertFalse(new JournalDevices(store).authorize(current).path("revoked").asBoolean());return null;});
        assertEquals("JOURNAL_KEY_ALREADY_ENROLLED",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).enroll("Duplicate",key,null))).code);
    }
}
