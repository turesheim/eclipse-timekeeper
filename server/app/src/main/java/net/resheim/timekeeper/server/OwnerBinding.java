package net.resheim.timekeeper.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Optional client session binding, encoded for HTTP headers even for Unicode owner IDs. */
final class OwnerBinding {
    static final String HEADER="X-Timekeeper-Owner";
    static String encode(String owner) {return Base64.getUrlEncoder().withoutPadding().encodeToString(owner.getBytes(StandardCharsets.UTF_8));}
    static String decode(String value) {
        try {
            if(value==null || value.length()>1100 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            String owner=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if(owner.isBlank() || !owner.equals(owner.trim()) || owner.codePointCount(0,owner.length())>200 || !encode(owner).equals(value)) throw new IllegalArgumentException();
            return owner;
        } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {throw ApiFailure.badRequest();}
    }
}
