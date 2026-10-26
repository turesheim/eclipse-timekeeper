package net.resheim.timekeeper.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Public Ed25519 material only; never accepts authorized_keys options or private keys. */
record JournalKey(String publicKey,String fingerprint) {
    static JournalKey parse(String text){
        try{
            if(text==null||text.length()>4096||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new IllegalArgumentException();
            var fields=text.strip().split("[ \\t]+",3);if(fields.length<2||!fields[0].equals("ssh-ed25519"))throw new IllegalArgumentException();
            byte[] raw=Base64.getDecoder().decode(fields[1]);if(raw.length!=51)throw new IllegalArgumentException();
            var bytes=ByteBuffer.wrap(raw);if(bytes.getInt()!=11)throw new IllegalArgumentException();var type=new byte[11];bytes.get(type);
            if(!new String(type,StandardCharsets.US_ASCII).equals("ssh-ed25519")||bytes.getInt()!=32||bytes.remaining()!=32)throw new IllegalArgumentException();
            String fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw));
            return new JournalKey("ssh-ed25519 "+Base64.getEncoder().encodeToString(raw),fingerprint);
        }catch(IllegalArgumentException failure){throw new ApiFailure(422,"JOURNAL_PUBLIC_KEY_REQUIRED");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    @Override public String toString(){return "JournalKey["+fingerprint+"]";}
}
