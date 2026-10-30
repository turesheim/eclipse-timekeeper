package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultContentTest.Profile.class)
class VaultContentTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_content_test");}}
    @Inject ServerStore store;
    @Test void opaqueMarkdownAndBinaryBytesSurviveAndNoopsDoNotAdvanceVersions(){
        UUID note=UUID.randomUUID(),attachment=UUID.randomUUID();
        byte[] markdown="\ufeff---\r\nunknown: [keep, true]\r\n---\r\n# Ø  \r\n[[A note|alias]]\r\n![[drawing.canvas]]\r\n\t  ".getBytes(StandardCharsets.UTF_8);
        byte[] binary=new byte[256];for(int i=0;i<binary.length;i++)binary[i]=(byte)i;
        store.transaction("a",()->{
            var content=new VaultContent(store);var saved=content.save(note,etag(content.metadata(note)),markdown,1000);
            assertFalse(saved.has("data"));assertArrayEquals(markdown,content.read(note));
            assertEquals(etag(saved),etag(content.save(note,etag(saved),markdown,1000)));
            content.save(attachment,etag(content.metadata(attachment)),binary,1000);assertArrayEquals(binary,content.read(attachment));return null;
        });
        store.transaction("b",()->{
            var content=new VaultContent(store);assertEquals(0,content.metadata(note).path("version").asLong());assertThrows(ApiFailure.class,()->content.read(note));return null;
        });
    }
    @Test void staleUpdatesDeleteRecreateAndRestoreCannotOverwriteNewerContent(){
        UUID id=UUID.randomUUID();String first=store.transaction("a",()->{var c=new VaultContent(store);return etag(c.save(id,etag(c.metadata(id)),new byte[]{1},10));});
        String second=store.transaction("a",()->etag(new VaultContent(store).save(id,first,new byte[]{2},10)));
        var stale=assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultContent(store).save(id,first,new byte[]{3},10)));assertEquals(412,stale.status);
        String deleted=store.transaction("a",()->{var c=new VaultContent(store);var value=c.delete(id,second);assertThrows(ApiFailure.class,()->c.read(id));return etag(value);});
        String recreated=store.transaction("a",()->etag(new VaultContent(store).save(id,deleted,new byte[]{4},10)));
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultContent(store).restore(id,deleted)));
        store.transaction("a",()->{var c=new VaultContent(store);assertArrayEquals(new byte[]{4},c.read(id));assertEquals(recreated,etag(c.metadata(id)));return null;});
    }
    @Test void deletionRecoveryRetainsExactBytesAndIsGuarded(){
        UUID id=UUID.randomUUID();byte[] bytes="Recover me  \r\n".getBytes(StandardCharsets.UTF_8);
        store.transaction("a",()->{var c=new VaultContent(store);var saved=c.save(id,etag(c.metadata(id)),bytes,100);
            var removed=c.delete(id,etag(saved));var restored=c.restore(id,etag(removed));
            assertTrue(restored.path("version").asLong()>removed.path("version").asLong());assertArrayEquals(bytes,c.read(id));return null;});
    }
    @Test void rejectedSizeAndUtf8NeverChangeTheExistingFile(){
        UUID id=UUID.randomUUID();String tag=store.transaction("a",()->{var c=new VaultContent(store);return etag(c.save(id,etag(c.metadata(id)),new byte[]{1},10));});
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultContent(store).save(id,tag,new byte[11],10)));
        assertThrows(ApiFailure.class,()->VaultContent.markdown(new byte[]{(byte)0xc3,0x28},100));
        assertThrows(ApiFailure.class,()->VaultContent.markdown(new byte[11],10));
        store.transaction("a",()->{var c=new VaultContent(store);assertEquals(tag,etag(c.metadata(id)));assertArrayEquals(new byte[]{1},c.read(id));return null;});
    }
    @Test void damagedStoredBytesCannotBeServedWithAnUnrelatedStrongEtag(){
        UUID id=UUID.randomUUID();
        store.transaction("a",()->{
            var c=new VaultContent(store);c.save(id,etag(c.metadata(id)),new byte[]{1},10);
            var damaged=(com.fasterxml.jackson.databind.node.ObjectNode)store.find(VaultContent.TYPE,id).orElseThrow();damaged.put("data",Base64.getEncoder().encodeToString(new byte[]{2}));
            store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",damaged.toString(),store.owner(),VaultContent.TYPE,id);return null;
        });
        var failure=assertThrows(IllegalStateException.class,()->store.transaction("a",()->new VaultContent(store).read(id)));
        assertEquals("Invalid canonical vault content",failure.getMessage());
    }
}
