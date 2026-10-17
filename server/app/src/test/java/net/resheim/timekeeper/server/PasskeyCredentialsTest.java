package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.yubico.webauthn.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasskeyCredentialsTest {
    @org.junit.jupiter.api.extension.RegisterExtension final PasskeyTestDatabase database=new PasskeyTestDatabase();
    private static final String RP="timekeeper.example",ORIGIN="https://timekeeper.example:8443";
    @TempDir Path directory;
    private void account(Connection c,String owner) {
        execute(c,"INSERT INTO tk_owner VALUES(?,'UTC',0,0,'cursor')",owner);
        execute(c,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES(?,?,?,?,'ENABLED',FALSE,0)",owner,owner,"unchanged hash",owner);
    }
    private PasskeyCredentials.Stored enroll(PasskeyCredentials repository,String owner,WebAuthnProtocolTest.Authenticator authenticator) throws Exception {
        var protocol=new WebAuthnProtocol(RP,ORIGIN,repository);
        var options=protocol.registration(UserIdentity.builder().name(owner).displayName(owner).id(repository.handle(owner)).build());
        return repository.add(owner,"My passkey",protocol.register(options,authenticator.registration(options,RP,ORIGIN,0x5d)),Instant.parse("2026-10-17T00:00:00Z"));
    }
    @Test void signedUseRenameRevocationAndRestartPreserveOpaqueOwnershipAndStableManagementVersions() throws Exception {
        String url="jdbc:h2:"+directory.resolve("passkeys");UUID keyId;ByteArray handle,credentialId;
        try(var c=database.connection(url)) {
            ServerSchema.initializeOrValidate(c);c.setAutoCommit(false);lock(c);account(c,"alice");account(c,"bob");
            var repository=new PasskeyCredentials(c,RP);handle=repository.handle("alice");assertEquals(32,handle.size());assertEquals(handle,repository.handle("alice"));assertNotEquals(handle,repository.handle("bob"));
            var authenticator=new WebAuthnProtocolTest.Authenticator("shared stored passkey");var stored=enroll(repository,"alice",authenticator);keyId=stored.id();credentialId=stored.credentialId();
            assertEquals(404,assertThrows(ApiFailure.class,()->repository.owned("bob",stored.id())).status);
            assertFalse(stored.listed(RP).has("credentialId"));assertFalse(stored.management().has("publicKey"));assertFalse(stored.listed("new.example").path("usableHere").asBoolean());
            String managementTag=etag(stored.management());var protocol=new WebAuthnProtocol(RP,ORIGIN,repository);var request=protocol.assertion(Optional.empty());
            var used=repository.used(protocol.authenticate(request,authenticator.assertion(request,RP,ORIGIN,0x1d,0,handle,false)),Instant.parse("2026-10-17T01:00:00Z"));
            assertEquals(managementTag,etag(used.management()));assertNotNull(used.lastUsed());
            var renamed=repository.rename("alice",keyId,managementTag,"Laptop and phone");assertEquals("Laptop and phone",renamed.name());
            assertEquals(412,assertThrows(ApiFailure.class,()->repository.rename("alice",stored.id(),managementTag,"Stale rename")).status);
            execute(c,"UPDATE tk_account SET username='renamed-alice' WHERE owner_id='alice'");assertEquals("renamed-alice",repository.getUsernameForUserHandle(handle).orElseThrow());
            assertEquals(handle,repository.getUserHandleForUsername("renamed-alice").orElseThrow());c.commit();
        }
        try(var c=database.connection(url)) {
            ServerSchema.validate(c);c.setAutoCommit(false);lock(c);var repository=new PasskeyCredentials(c,RP);var stored=repository.owned("alice",keyId);
            assertEquals(handle,stored.handle());assertEquals(credentialId,stored.credentialId());assertEquals("Laptop and phone",stored.name());
            execute(c,"INSERT INTO tk_auth_session(digest,owner_id,csrf,permissions,created_at,expires_at,last_seen_at,persistent,passkey_id) VALUES('session','alice','csrf','READ_WRITE','2026-10-17T00:00:00Z','2026-10-18T00:00:00Z','2026-10-17T00:00:00Z',FALSE,?)",keyId);
            repository.revoke("alice",keyId,etag(stored.management()));assertTrue(repository.credential(credentialId,true).isEmpty());assertTrue(repository.credential(credentialId,false).orElseThrow().revoked());
            assertTrue(repository.lookupAll(credentialId).isEmpty());
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM tk_auth_session")) {rows.next();assertEquals(0,rows.getInt(1));}
            assertTrue(new PasskeyCredentials(c,"new.example").lookupAll(credentialId).isEmpty());c.commit();
        }
    }
    @Test void tombstonesPreventCredentialReassignmentAndRecoveryAffectsOnlyItsOwner() throws Exception {
        try(var c=database.connection("jdbc:h2:mem:passkey_owner_"+UUID.randomUUID())) {
            ServerSchema.initializeOrValidate(c);c.setAutoCommit(false);lock(c);account(c,"alice");account(c,"bob");var repository=new PasskeyCredentials(c,RP);
            var authenticator=new WebAuthnProtocolTest.Authenticator("credential may not transfer");var key=enroll(repository,"alice",authenticator);
            repository.revoke("alice",key.id(),etag(key.management()));
            assertEquals(409,assertThrows(ApiFailure.class,()->enroll(repository,"bob",authenticator)).status);
            var alice=enroll(repository,"alice",new WebAuthnProtocolTest.Authenticator("replacement alice"));var bob=enroll(repository,"bob",new WebAuthnProtocolTest.Authenticator("independent bob"));
            execute(c,"INSERT INTO tk_passkey_ceremony VALUES('pending','browser','REGISTER','alice','session','{}','2026-10-17T01:00:00Z',FALSE)");
            PasskeyCredentials.recover(c,"alice");assertTrue(repository.credential(alice.credentialId(),true).isEmpty());assertTrue(repository.credential(bob.credentialId(),true).isPresent());
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT consumed FROM tk_passkey_ceremony WHERE digest='pending'")) {rows.next();assertTrue(rows.getBoolean(1));}
            c.commit();
        }
    }
}
