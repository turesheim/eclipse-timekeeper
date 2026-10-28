package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AccountPasswordsTest {
    @Test void supportedHashesUseTheSameParametersAsRuntimeVerification() {
        String password="Supported existing account password",encoded=AccountPasswords.hash(password);assertTrue(AccountPasswords.supported(encoded));assertTrue(AccountPasswords.matches(password,encoded));
        for(String invalid:new String[]{null,"","unsupported",encoded.replace("600000","1"),encoded.replace("600000","2000001"),"pbkdf2-sha256$600000$AA$AA","pbkdf2-sha256$invalid$AA$AA"}) {assertFalse(AccountPasswords.supported(invalid));assertFalse(AccountPasswords.matches(password,invalid));}
    }
    @Test void saltedHashesRetainLongUnicodePasswordsWithoutTrimmingOrTruncation() {
        String password="  ø 🧩 passphrase "+"long".repeat(30)+"  ";
        var first=AccountPasswords.hash(password);var second=AccountPasswords.hash(password);
        assertNotEquals(first,second);assertFalse(first.contains(password));assertTrue(AccountPasswords.matches(password,first));
        assertFalse(AccountPasswords.matches(password.strip(),first));assertFalse(AccountPasswords.matches(password+"x",first));
        assertFalse(AccountPasswords.matches(password,"invalid"));assertFalse(AccountPasswords.matches(password,first.replace("600000","1")));
        assertThrows(ApiFailure.class,()->AccountPasswords.hash("too short"));assertThrows(ApiFailure.class,()->AccountPasswords.hash("x".repeat(1025)));
        assertNotEquals(AccountPasswords.secret(),AccountPasswords.secret());assertEquals(43,AccountPasswords.secret().length());
    }
}
