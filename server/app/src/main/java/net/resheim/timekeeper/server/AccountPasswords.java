package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Versioned salted password hashes using the JDK's PBKDF2-HMAC-SHA256 implementation. */
final class AccountPasswords {
    static final int ITERATIONS=600_000;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private AccountPasswords() { }
    static String secret() {byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return ENCODER.encodeToString(bytes);}
    static String hash(String password) {
        validate(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$"+ITERATIONS+"$"+ENCODER.encodeToString(salt)+"$"+ENCODER.encodeToString(derive(password,salt,ITERATIONS));
    }
    static void validate(String password) {
        // Long passphrases and Unicode are supported; no silent trimming or truncation.
        if(password==null || password.codePointCount(0,password.length())<15 || password.getBytes(StandardCharsets.UTF_8).length>1024) throw ApiFailure.validation();
    }
    private record Encoded(int rounds,byte[] salt,byte[] expected) implements AutoCloseable {
        @Override public void close(){Arrays.fill(salt,(byte)0);Arrays.fill(expected,(byte)0);}
        @Override public String toString(){return "Private password hash";}
    }
    private static Encoded decode(String encoded) {
        var parts=encoded.split("\\$",-1);if(parts.length!=4||!parts[0].equals("pbkdf2-sha256"))throw new IllegalArgumentException();
        int rounds=Integer.parseInt(parts[1]);if(rounds<ITERATIONS||rounds>2_000_000)throw new IllegalArgumentException();
        byte[] salt=null,expected=null;boolean valid=false;
        try {
            salt=Base64.getUrlDecoder().decode(parts[2]);expected=Base64.getUrlDecoder().decode(parts[3]);
            if(salt.length!=16||expected.length!=32)throw new IllegalArgumentException();
            valid=true;return new Encoded(rounds,salt,expected);
        }finally{if(!valid){if(salt!=null)Arrays.fill(salt,(byte)0);if(expected!=null)Arrays.fill(expected,(byte)0);}}
    }
    /** Check supported parameters without needing or guessing a plaintext password. */
    static boolean supported(String encoded) {
        try(var value=decode(encoded)){return true;}catch(RuntimeException invalid){return false;}
    }
    static boolean matches(String password,String encoded) {
        if(password==null||password.getBytes(StandardCharsets.UTF_8).length>1024)return false;
        try(var value=decode(encoded)) {
            byte[] actual=derive(password,value.salt(),value.rounds());
            try{return MessageDigest.isEqual(value.expected(),actual);}finally{Arrays.fill(actual,(byte)0);}
        }catch(RuntimeException invalid){return false;}
    }
    private static byte[] derive(String password,byte[] salt,int rounds) {
        char[] chars=password.toCharArray();var key=new PBEKeySpec(chars,salt,rounds,256);
        try {return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();}
        catch(Exception failure) {throw new IllegalStateException("Password hashing unavailable",failure);}
        finally {key.clearPassword();Arrays.fill(chars,'\0');}
    }
}
