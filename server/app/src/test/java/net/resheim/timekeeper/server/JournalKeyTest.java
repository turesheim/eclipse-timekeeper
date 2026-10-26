package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class JournalKeyTest {
    static String publicKey()throws Exception{
        byte[] encoded=KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        var wire=ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).putInt(32).put(encoded,encoded.length-32,32).array();
        return "ssh-ed25519 "+Base64.getEncoder().encodeToString(wire);
    }
    @Test void acceptsOnlyPublicEd25519WireFormatAndDiscardsComments()throws Exception{
        String key=publicKey();var parsed=JournalKey.parse("  "+key+" device label  ");assertEquals(key,parsed.publicKey());
        assertEquals("SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key.split(" ")[1]))),parsed.fingerprint());
        assertFalse(parsed.toString().contains(key));
    }
    @Test void rejectsPrivateKeysOptionsOtherAlgorithmsAndMalformedWireFormat()throws Exception{
        String key=publicKey();
        for(String invalid:List.of("-----BEGIN OPENSSH PRIVATE KEY-----\nprivate\n-----END OPENSSH PRIVATE KEY-----", "command=\"shell\" "+key,"restrict "+key,"ssh-rsa AAAA",key+"\nsecond",key+"\r", "ssh-ed25519 !!!","ssh-ed25519 "+Base64.getEncoder().encodeToString(new byte[51]),key.substring(0,key.length()-4))){
            var failure=assertThrows(ApiFailure.class,()->JournalKey.parse(invalid));assertEquals("JOURNAL_PUBLIC_KEY_REQUIRED",failure.code);assertNull(failure.getCause());assertFalse(failure.toString().contains("private"));
        }
    }
}
