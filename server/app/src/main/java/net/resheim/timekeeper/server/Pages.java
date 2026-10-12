package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Signed owner/filter/revision-bound keyset cursors survive server restart. */
@ApplicationScoped
public class Pages {
    @Inject ServerStore store;
    private byte[] signature(String payload) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(store.cursorSecret(),"HmacSHA256"));return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String encode(JsonNode cursor) {
        String payload=JsonValues.canonical(cursor).toString(); var encoder=Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(signature(payload));
    }
    private JsonNode decode(String cursor) {
        try {
            if(cursor.length()>4096) throw ApiFailure.badRequest();String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2) throw ApiFailure.badRequest(); var decoder=Base64.getUrlDecoder();
            String payload=new String(decoder.decode(parts[0]),StandardCharsets.UTF_8);
            if(!MessageDigest.isEqual(signature(payload),decoder.decode(parts[1]))) throw ApiFailure.badRequest();
            return JsonValues.MAPPER.readTree(payload);
        } catch(Exception e) { throw ApiFailure.badRequest(); }
    }
    public ObjectNode page(String type,List<JsonNode> values,JsonNode filters,int limit,String cursor) {
        long revision=store.revision();int offset=0;
        if(cursor!=null) {
            JsonNode previous=decode(cursor);
            if(!previous.path("owner").asText().equals(store.owner()) || !previous.path("type").asText().equals(type) || !previous.path("filters").equals(filters)) throw ApiFailure.badRequest();
            if(previous.path("revision").asLong(-1)!=revision) throw new ApiFailure(409,"CURSOR_EXPIRED");
            String id=previous.path("lastId").asText();
            int index=-1;for(int i=0;i<values.size();i++) if(values.get(i).get("id").asText().equals(id)) { index=i;break; }
            if(index<0) throw ApiFailure.badRequest();offset=index+1;
        }
        int end=Math.min(values.size(),offset+limit);
        var result=JsonValues.object();var items=result.putArray("items");values.subList(offset,end).forEach(items::add);result.put("revision",revision);
        if(end==values.size()) result.putNull("nextCursor");
        else {
            var next=JsonValues.object();next.put("owner",store.owner());next.put("type",type);next.set("filters",filters);next.put("revision",revision);next.put("lastId",values.get(end-1).get("id").asText());
            result.put("nextCursor",encode(next));
        }
        return result;
    }
}
