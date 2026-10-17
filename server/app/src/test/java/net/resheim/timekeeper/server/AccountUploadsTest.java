package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(AccountUploadsTest.Profile.class)
class AccountUploadsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return ArchiveTestDatabase.configuration("upload","jdbc:h2:mem:account_upload_tests");}
    }
    @Inject ServerStore store;
    private static String encoded(byte[] bytes) {return Base64.getEncoder().encodeToString(bytes);}
    private static String digest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void chunksArePrivateSequentialReplayableAndVerifiedBeforeReview() throws Exception {
        String owner="archive-upload-"+UUID.randomUUID();var uploads=new AccountUploads(store);
        byte[] bytes=new byte[AccountUploads.CHUNK_BYTES+7];new Random(7).nextBytes(bytes);String hash=digest(bytes);
        var first=encoded(Arrays.copyOf(bytes,AccountUploads.CHUNK_BYTES));var last=encoded(Arrays.copyOfRange(bytes,AccountUploads.CHUNK_BYTES,bytes.length));
        var upload=store.transaction(owner,()->uploads.create("account.zip","application/zip",bytes.length,hash));UUID id=UUID.fromString(upload.path("id").asText());
        store.transaction(owner,()->{
            assertEquals(409,assertThrows(ApiFailure.class,()->uploads.chunk(id,1,last)).status);
            var saved=uploads.chunk(id,0,first);assertEquals(1,saved.path("nextChunk").asInt());
            assertEquals(saved.toString(),uploads.chunk(id,0,first).toString());
            byte[] altered=Arrays.copyOf(bytes,AccountUploads.CHUNK_BYTES);altered[0]^=1;
            assertEquals(409,assertThrows(ApiFailure.class,()->uploads.chunk(id,0,encoded(altered))).status);
            assertEquals("READY",uploads.chunk(id,1,last).path("state").asText());return null;
        });
        store.transaction("other-"+owner,()->{assertEquals(404,assertThrows(ApiFailure.class,()->uploads.get(id)).status);return null;});
        var restored=store.transaction(owner,()->uploads.materialize(id));var path=restored.file();
        try(restored) {assertArrayEquals(bytes,Files.readAllBytes(path));}assertFalse(Files.exists(path));
        store.transaction(owner,()->{uploads.remove(id);assertTrue(store.list(AccountUploads.CHUNK).isEmpty());assertTrue(store.list(AccountUploads.UPLOAD).isEmpty());return null;});
    }
    @Test void malformedLengthsHashesAndStorageQuotasAreExplicit() throws Exception {
        String owner="archive-quota-"+UUID.randomUUID();var uploads=new AccountUploads(store);byte[] bytes={1,2,3};String hash=digest(bytes);
        store.transaction(owner,()->{
            assertEquals(422,assertThrows(ApiFailure.class,()->uploads.create("account.zip","application/zip",AccountUploads.MAX_BYTES+1,hash)).status);
            assertEquals(422,assertThrows(ApiFailure.class,()->uploads.create("account.zip",null,3,hash)).status);
            var bad=uploads.create("bad.zip","application/zip",3,"0".repeat(64));UUID id=UUID.fromString(bad.path("id").asText());
            assertEquals(422,assertThrows(ApiFailure.class,()->uploads.chunk(id,0,"AQI=")).status);
            assertEquals(422,assertThrows(ApiFailure.class,()->uploads.chunk(id,0,"AQID=")).status);
            uploads.chunk(id,0,encoded(bytes));assertEquals(422,assertThrows(ApiFailure.class,()->uploads.materialize(id)).status);
            uploads.create("second.zip","application/zip",3,hash);uploads.create("third.zip","application/zip",3,hash);
            assertEquals(413,assertThrows(ApiFailure.class,()->uploads.create("fourth.zip","application/zip",3,hash)).status);
            var expired=uploads.get(id);expired.put("expiresAt",store.now().minusSeconds(1).toString());uploads.update(expired);
            uploads.create("replacement.zip","application/zip",3,hash);
            assertEquals(3,store.list(AccountUploads.UPLOAD).size());assertTrue(store.list(AccountUploads.CHUNK).isEmpty());return null;
        });
    }
}
