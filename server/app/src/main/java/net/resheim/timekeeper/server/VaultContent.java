package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical bytes for general documents. Journal/event content stays in its existing resource. */
final class VaultContent {
    static final String TYPE="VAULT_CONTENT";
    private final ServerStore store;
    VaultContent(ServerStore store){this.store=store;}
    private ObjectNode record(UUID id){
        return store.find(TYPE,id).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->object()
                .put("id",id.toString()).put("ownerId",store.owner()).put("version",0).put("deleted",true)
                .put("byteLength",0).put("sha256",ArchiveJournalFiles.digest(new byte[0])));
    }
    ObjectNode metadata(UUID id){return publicValue(record(id));}
    byte[] read(UUID id){var value=record(id);if(value.path("version").asLong()==0||value.path("deleted").asBoolean())throw ApiFailure.notFound();return bytes(value);}
    ObjectNode save(UUID id,String expected,byte[] bytes,int maximum){
        if(bytes==null||maximum<0||bytes.length>maximum)throw new ApiFailure(413,"VAULT_FILE_TOO_LARGE");
        var current=record(id);ApiReplies.match(publicValue(current),expected);
        if(current.path("version").asLong()>0&&!current.path("deleted").asBoolean()&&Arrays.equals(bytes(current),bytes))return publicValue(current);
        var value=current.deepCopy().put("deleted",false).put("byteLength",bytes.length)
                .put("sha256",ArchiveJournalFiles.digest(bytes)).put("data",Base64.getEncoder().encodeToString(bytes));
        return persist(id,current,value);
    }
    ObjectNode delete(UUID id,String expected){
        var current=record(id);ApiReplies.match(publicValue(current),expected);
        if(current.path("version").asLong()==0)throw ApiFailure.notFound();
        if(current.path("deleted").asBoolean())return publicValue(current);
        return persist(id,current,current.deepCopy().put("deleted",true));
    }
    ObjectNode restore(UUID id,String expected){
        var current=record(id);ApiReplies.match(publicValue(current),expected);
        if(current.path("version").asLong()==0)throw ApiFailure.notFound();
        if(!current.path("deleted").asBoolean())return publicValue(current);
        return persist(id,current,current.deepCopy().put("deleted",false));
    }
    static String markdown(byte[] bytes,int maximum){
        if(bytes==null||bytes.length>maximum)throw new ApiFailure(413,"VAULT_FILE_TOO_LARGE");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw ApiFailure.validation();}
    }
    private ObjectNode persist(UUID id,ObjectNode previous,ObjectNode value){
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        value.put("version",version+1).put("modifiedAt",store.now().toString());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,id,version+1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,id,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        return publicValue(value);
    }
    private static byte[] bytes(ObjectNode value){
        try{
            if(!value.path("data").isTextual())throw new IllegalStateException("Invalid canonical vault content");
            byte[] bytes=Base64.getDecoder().decode(value.path("data").asText());
            if(bytes.length!=value.path("byteLength").asLong(-1)||!ArchiveJournalFiles.digest(bytes).equals(value.path("sha256").asText()))
                throw new IllegalStateException("Invalid canonical vault content");
            return bytes;
        }
        catch(IllegalArgumentException invalid){throw new IllegalStateException("Invalid canonical vault content",invalid);}
    }
    private static ObjectNode publicValue(ObjectNode value){var result=value.deepCopy();result.remove("data");return result;}
}
