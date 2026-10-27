package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Account-scoped binary companions to DAY_NOTE Markdown, stored in the existing resource table. */
final class JournalAssets {
    static final String TYPE="JOURNAL_ASSET";
    static final int MAX_BYTES=5_000_000;
    static final long MAX_TOTAL_BYTES=100_000_000;
    static final int MAX_COUNT=1000;
    private static final Pattern NAME=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private final ServerStore store;

    JournalAssets(ServerStore store){this.store=store;}

    static String name(String value){
        if(value==null||!NAME.matcher(value).matches()||value.equals(".")||value.equals(".."))throw ApiFailure.notFound();
        return value;
    }

    private UUID id(String filename){
        return UUID.nameUUIDFromBytes((store.owner()+"\0journal-asset\0"+filename).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode get(String filename){
        filename=name(filename);
        var value=store.find(TYPE,id(filename)).orElseThrow(ApiFailure::notFound);
        if(value.path("deleted").asBoolean()||!filename.equals(value.path("filename").asText()))throw ApiFailure.notFound();
        return value;
    }

    JsonNode existing(String filename){return store.find(TYPE,id(name(filename))).orElse(null);}

    List<JsonNode> list(){
        var result=new ArrayList<JsonNode>();
        for(var value:store.list(TYPE))if(!value.path("deleted").asBoolean())result.add(value);
        result.sort(java.util.Comparator.comparing(value->value.path("filename").asText()));
        return List.copyOf(result);
    }

    static byte[] bytes(JsonNode value){
        try{return Base64.getDecoder().decode(value.path("data").asText());}
        catch(IllegalArgumentException invalid){throw new IllegalStateException("Invalid journal asset payload",invalid);}
    }

    static String mediaType(String filename,byte[] bytes){
        String lower=filename.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".png")&&bytes.length>=8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'
                &&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10)return "image/png";
        if((lower.endsWith(".jpg")||lower.endsWith(".jpeg"))&&bytes.length>=3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8&&bytes[2]==(byte)0xff)return "image/jpeg";
        if(lower.endsWith(".gif")&&bytes.length>=6&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F'
                &&bytes[3]=='8'&&(bytes[4]=='7'||bytes[4]=='9')&&bytes[5]=='a')return "image/gif";
        if(lower.endsWith(".webp")&&bytes.length>=12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'
                &&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P')return "image/webp";
        return "application/octet-stream";
    }

    JsonNode save(String filename,String expected,byte[] bytes){
        filename=name(filename);
        if(bytes==null||bytes.length>MAX_BYTES)throw new ApiFailure(413,"JOURNAL_ASSET_TOO_LARGE");
        var previous=existing(filename);
        long version=previous==null?0:previous.path("version").asLong();
        String current=previous==null?null:etag(previous);
        if(expected!=null&&!expected.equals(current))throw new ApiFailure(412,"STALE_VERSION");
        if(previous!=null&&!previous.path("deleted").asBoolean()&&java.util.Arrays.equals(bytes(previous),bytes))return previous;
        long total=0;int count=0;
        for(var asset:list()){total+=asset.has("byteLength")?asset.path("byteLength").asLong():bytes(asset).length;count++;}
        if(previous!=null&&!previous.path("deleted").asBoolean()){
            total-=previous.has("byteLength")?previous.path("byteLength").asLong():bytes(previous).length;count--;
        }
        if(count>=MAX_COUNT||total+bytes.length>MAX_TOTAL_BYTES)throw new ApiFailure(413,"JOURNAL_ASSET_QUOTA");
        var value=object().put("id",id(filename).toString()).put("ownerId",store.owner()).put("version",version+1)
                .put("filename",filename).put("mediaType",mediaType(filename,bytes))
                .put("data",Base64.getEncoder().encodeToString(bytes)).put("byteLength",bytes.length)
                .put("modifiedAt",Instant.now().toString()).put("deleted",false);
        persist(value,version);
        store.change("PROFILE","UPDATED",store.owner());
        return value;
    }

    JsonNode delete(String filename,String expected){
        filename=name(filename);
        var previous=get(filename);
        if(expected==null||!expected.equals(etag(previous)))throw new ApiFailure(412,"STALE_VERSION");
        long version=previous.path("version").asLong();
        var value=((ObjectNode)previous).deepCopy();value.put("version",version+1);value.put("deleted",true);
        value.remove("data");value.put("modifiedAt",Instant.now().toString());persist(value,version);
        store.change("PROFILE","UPDATED",store.owner());return value;
    }

    void restore(JsonNode assets){
        var names=new java.util.HashSet<String>();
        for(var asset:assets){
            if(!asset.isObject()||asset.size()!=2||!asset.path("filename").isTextual()||!asset.path("data").isTextual())throw ApiFailure.validation();
            String filename=name(asset.path("filename").asText());
            if(!names.add(filename))throw ApiFailure.validation();
            String encoded=asset.path("data").asText();
            if(encoded.length()>(MAX_BYTES+2)/3*4+4)throw new ApiFailure(413,"JOURNAL_ASSET_TOO_LARGE");
            final byte[] bytes;
            try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
            if(bytes.length>MAX_BYTES)throw new ApiFailure(413,"JOURNAL_ASSET_TOO_LARGE");
            var previous=existing(filename);
            if(previous!=null&&!previous.path("deleted").asBoolean()){
                if(!java.util.Arrays.equals(bytes(previous),bytes))throw ApiFailure.conflict();
                continue;
            }
            save(filename,null,bytes);
        }
    }

    private void persist(ObjectNode value,long previous){
        UUID id=id(value.path("filename").asText());
        if(previous==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",
                store.owner(),TYPE,id,1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",
                previous+1,value.toString(),store.owner(),TYPE,id,previous)!=1)throw new ApiFailure(412,"STALE_VERSION");
    }
}
