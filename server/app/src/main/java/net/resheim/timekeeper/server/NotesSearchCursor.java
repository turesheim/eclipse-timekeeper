package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;

/** Owner-signed logical positions; no replica-local Lucene document IDs escape. */
final class NotesSearchCursor {
    static final long LIFETIME_SECONDS=600;
    private final byte[] key;
    private final String owner;
    private final Instant now;
    record Frame(long expires,NotesLuceneIndex.Position after){
        Frame position(NotesLuceneIndex.Position position){return new Frame(expires,position);}
        @Override public String toString(){return "NotesSearchCursor.Frame[]";}
    }
    NotesSearchCursor(byte[] key,String owner,Instant now){
        if(key==null||key.length!=32)throw new IllegalArgumentException("Invalid cursor key");this.key=key.clone();this.owner=Objects.requireNonNull(owner);this.now=Objects.requireNonNull(now);
    }
    private String scope(NotesSearchSources.State state,NotesSearchQuery.Parsed query){
        var identity=state.identity();if(!owner.equals(identity.owner()))throw new IllegalStateException("Cross-owner search cursor");
        var value=object().put("format",NotesLuceneIndex.FORMAT).put("owner",owner).put("collection",identity.collection().toString()).put("epoch",identity.epoch().toString())
                .put("restore",identity.restoreGeneration()).put("generation",state.changes().generation()).put("query",query.text()).put("configuration",state.configuration());
        if(state.hiddenJournal()!=null)value.put("hiddenJournal",VaultPath.searchKey(state.hiddenJournal()));
        return ArchiveJournalFiles.digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8));
    }
    Frame read(String cursor,NotesSearchSources.State state,NotesSearchQuery.Parsed query){
        String scope=scope(state,query);if(cursor==null)return new Frame(Math.addExact(now.getEpochSecond(),LIFETIME_SECONDS),null);
        try{
            if(cursor.length()>4096)throw expired();String[] parts=cursor.split("\\.",-1);if(parts.length!=2)throw expired();var decoder=Base64.getUrlDecoder();
            byte[] payload=decoder.decode(parts[0]),signature=decoder.decode(parts[1]);if(signature.length!=32||!MessageDigest.isEqual(sign(payload),signature))throw expired();
            JsonNode value=MAPPER.readTree(payload);
            if(value==null||!value.isObject()||value.size()!=4||value.path("version").asInt()!=1||!value.path("scope").asText().equals(scope)||!value.path("expires").isIntegralNumber())throw expired();
            long expires=value.path("expires").asLong();if(expires<=now.getEpochSecond())throw expired();
            var after=value.path("after");if(after.isNull())return new Frame(expires,null);
            if(!after.isObject()||after.size()!=3||!after.path("score").isInt()||!after.path("path").isTextual()||!after.path("id").isTextual())throw expired();
            String path=after.path("path").asText();if(path.isEmpty()||path.length()>4096)throw expired();
            return new Frame(expires,new NotesLuceneIndex.Position(Float.intBitsToFloat(after.path("score").asInt()),path,UUID.fromString(after.path("id").asText())));
        }catch(ApiFailure failure){throw failure;}catch(Exception invalid){throw expired();}
    }
    String write(Frame frame,NotesSearchSources.State state,NotesSearchQuery.Parsed query){
        var value=object().put("version",1).put("scope",scope(state,query)).put("expires",frame.expires());
        if(frame.after()==null)value.putNull("after");else{var after=value.putObject("after");after.put("score",Float.floatToIntBits(frame.after().score())).put("path",frame.after().pathKey()).put("id",frame.after().id().toString());}
        byte[] payload=canonical(value).toString().getBytes(StandardCharsets.UTF_8);var encoder=Base64.getUrlEncoder().withoutPadding();
        String result=encoder.encodeToString(payload)+"."+encoder.encodeToString(sign(payload));if(result.length()>4096)throw new IllegalArgumentException("Search position exceeds cursor budget");return result;
    }
    private byte[] sign(byte[] payload){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));mac.update("notes-search-cursor-v1\0".getBytes(StandardCharsets.US_ASCII));return mac.doFinal(payload);}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Cursor signing unavailable");}
    }
    private static ApiFailure expired(){return new ApiFailure(412,"NOTES_SEARCH_SNAPSHOT_EXPIRED");}
}
