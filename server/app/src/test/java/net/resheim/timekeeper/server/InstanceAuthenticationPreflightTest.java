package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceAuthenticationPreflightTest {
    @TempDir Path directory;
    /** Keep image portability's deliberately opaque fixtures separate from usable authentication. */
    static void seed(Connection c) throws Exception {
        InstanceDatabaseImageTest.seed(c);
        execute(c,"UPDATE tk_account SET password_hash=?",AccountPasswords.hash("Preserved archive account password"));
        var effective=object().put("publicOrigin","https://source.example").put("originEpoch",3).put("passkeysEnabled",true).put("passkeyRpId","AUTO")
            .put("emailRecoveryEnabled",true).put("smtpHost","smtp.example").put("smtpPort",587).put("smtpFrom","source@example.org").put("smtpUsername","private-user").put("smtpTls",false).put("smtpStartTls","REQUIRED");
        var saved=effective.deepCopy().put("smtpPort",2525);
        execute(c,"UPDATE tk_service_settings SET saved=?,effective=?",saved.toString(),effective.toString());
        var authenticator=new WebAuthnProtocolTest.Authenticator("Preserved archive credential");
        execute(c,"UPDATE tk_passkey_user SET user_handle=?",AccountPasswords.secret());
        execute(c,"UPDATE tk_passkey_credential SET credential_digest=?,credential_id=?,public_key=?",hash(authenticator.id.getBase64Url()),authenticator.id.getBase64Url(),authenticator.cose.getBase64Url());
        c.commit();
    }
    @Test void passwordsPasskeysTokensAndSettingsAreUsableAndRemainUnchanged() throws Exception {
        try(var c=InstanceDatabaseImageTest.database()) {
            seed(c);Path before=directory.resolve("before.json"),after=directory.resolve("after.json");
            try(var out=Files.newOutputStream(before)){InstanceDatabaseImage.write(c,out);}
            InstanceAuthenticationPreflight.inspect(c);
            try(var out=Files.newOutputStream(after)){InstanceDatabaseImage.write(c,out);}
            assertEquals(-1,Files.mismatch(before,after));
            try(var rows=c.createStatement().executeQuery("SELECT password_hash FROM tk_account WHERE owner_id='a'")){assertTrue(rows.next());assertTrue(AccountPasswords.matches("Preserved archive account password",rows.getString(1)));}
        }
    }
    @Test void unsupportedPasswordsOrMissingAdministratorCannotPassArchiveReview() throws Exception {
        try(var c=InstanceDatabaseImageTest.database()) {
            seed(c);
            for(String encoded:List.of("unsupported-private-hash","pbkdf2-sha256$1$invalid$invalid")){
                var save=c.setSavepoint();execute(c,"UPDATE tk_account SET password_hash=? WHERE owner_id='a'",encoded);rejects(c);c.rollback(save);
            }
            execute(c,"UPDATE tk_account SET administrator=FALSE");rejects(c);
        }
    }
    @Test void badCoseKeyDigestHandleTransportAndTimestampAreRejectedEvenWhenSqlConstraintsPass() throws Exception {
        try(var c=InstanceDatabaseImageTest.database()) {
            seed(c);
            for(String sql:List.of("UPDATE tk_passkey_credential SET public_key='aW52YWxpZA'","UPDATE tk_passkey_credential SET credential_digest='"+"d".repeat(64)+"'","UPDATE tk_passkey_user SET user_handle=''","UPDATE tk_passkey_credential SET transports='{}'","UPDATE tk_passkey_credential SET created_at='not-an-instant'","UPDATE tk_passkey_credential SET rp_id='https://invalid.example'")) {
                var save=c.setSavepoint();if(sql.contains("SET digest="))execute(c,"UPDATE tk_auth_session SET parent_digest=NULL");execute(c,sql);rejects(c);c.rollback(save);
            }
        }
    }
    @Test void brokenSettingsAndRecoveryBindingAreRejectedWithoutChangingSource() throws Exception {
        try(var c=InstanceDatabaseImageTest.database()) {
            seed(c);
            for(String sql:List.of("UPDATE tk_service_settings SET effective='{}'","UPDATE tk_service_settings SET saved='{}'","UPDATE tk_recovery_control SET epoch=4","UPDATE tk_recovery_control SET origin='https://different.example'","UPDATE tk_auth_control SET bootstrap_completed=FALSE","UPDATE tk_api_token SET digest='unsupported'","UPDATE tk_api_token SET expires_at='not-an-instant'")) {
                var save=c.setSavepoint();if(sql.contains("SET digest="))execute(c,"UPDATE tk_auth_session SET parent_digest=NULL");execute(c,sql);rejects(c);c.rollback(save);
            }
        }
    }
    @Test void archivedPasskeysFromAnOlderHostnameArePreservedForExplicitDeploymentReview() throws Exception {
        try(var c=InstanceDatabaseImageTest.database()) {
            seed(c);execute(c,"UPDATE tk_passkey_credential SET rp_id='previous.example'");InstanceAuthenticationPreflight.inspect(c);
        }
    }
    private static void rejects(Connection c){var failure=assertThrows(InstanceArchiveRejection.class,()->InstanceAuthenticationPreflight.inspect(c));assertEquals("INVALID_INSTANCE_AUTHENTICATION",failure.getMessage());assertNull(failure.getCause());}
}
