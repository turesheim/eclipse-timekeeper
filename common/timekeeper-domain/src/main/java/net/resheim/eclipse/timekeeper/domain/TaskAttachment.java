package net.resheim.eclipse.timekeeper.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Immutable owner-scoped task attachment with verified bytes and metadata. */
public record TaskAttachment(UUID id,UUID taskId,String fileName,String contentType,int bytes,
        String sha256,String data,Optional<String> providerId,Optional<String> remoteId,long version) {
    public static final int MAX_BYTES=1024*1024;
    public TaskAttachment {
        id=Values.required(id,"attachmentId");taskId=Values.required(taskId,"taskId");
        fileName=Values.required(fileName,"fileName");contentType=Values.required(contentType,"contentType");
        sha256=Values.required(sha256,"sha256").toLowerCase(Locale.ROOT);data=Values.required(data,"data");
        providerId=clean(providerId,200);remoteId=clean(remoteId,1000);
        if(fileName.length()>255 || fileName.equals(".") || fileName.equals("..") || fileName.indexOf('/')>=0 || fileName.indexOf('\\')>=0
                || fileName.codePoints().anyMatch(Character::isISOControl)
                || contentType.length()>200 || !contentType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
                || providerId.isPresent()!=remoteId.isPresent())
            throw new DomainValidationException("attachment","invalid attachment metadata");
        if(bytes<1 || bytes>MAX_BYTES || !sha256.matches("[0-9a-f]{64}") || data.length()>4*((MAX_BYTES+2)/3))
            throw new DomainValidationException("attachment","invalid attachment size or checksum");
        byte[] decoded;
        try {decoded=Base64.getDecoder().decode(data);} catch(IllegalArgumentException error) {
            throw new DomainValidationException("attachment","invalid Base64 content");
        }
        if(decoded.length!=bytes || !Base64.getEncoder().encodeToString(decoded).equals(data) || !digest(decoded).equals(sha256))
            throw new DomainValidationException("attachment","attachment content does not match metadata");
        version=Values.version(version);
    }
    private static Optional<String> clean(Optional<String> value,int max) {
        var result=value==null?Optional.<String>empty():value.map(String::trim).filter(text -> !text.isEmpty());
        if(result.filter(text -> text.length()>max || text.codePoints().anyMatch(Character::isISOControl)).isPresent())
            throw new DomainValidationException("attachment","invalid provider attachment identity");
        return result;
    }
    private static String digest(byte[] data) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
}
