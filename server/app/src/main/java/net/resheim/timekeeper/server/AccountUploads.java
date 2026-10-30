package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded owner-private staging; large archives do not increase unrelated HTTP request limits. */
final class AccountUploads {
    static final int CHUNK_BYTES=1024*1024;
    static final long MAX_BYTES=1024L*1024*1024;
    static final long MAX_JSON_BYTES=256L*1024*1024;
    static final String UPLOAD="ARCHIVE_UPLOAD",CHUNK="ARCHIVE_CHUNK";
    private final ServerStore store;
    AccountUploads(ServerStore store) {this.store=store;}

    ObjectNode create(String fileName,String mediaType,long bytes,String sha256) {
        if(fileName==null || fileName.isBlank() || fileName.length()>255 || fileName.chars().anyMatch(c->c<32 || c==127)
                || mediaType==null || !Set.of("application/zip","application/json").contains(mediaType) || bytes<1 || bytes>MAX_BYTES || mediaType.equals("application/json")&&bytes>MAX_JSON_BYTES
                || sha256==null || !sha256.matches("[a-f0-9]{64}")) throw ApiFailure.validation();
        cleanup();long retained=0;int active=0;
        for(var current:store.list(UPLOAD)) if(!current.path("state").asText().equals("IMPORTED")) {
            active++;retained+=current.path("bytes").asLong();
        }
        if(active>=3 || retained+bytes>2*MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        var value=object().put("fileName",fileName).put("mediaType",mediaType).put("bytes",bytes).put("sha256",sha256)
                .put("chunkBytes",CHUNK_BYTES).put("chunkCount",(bytes+CHUNK_BYTES-1)/CHUNK_BYTES).put("nextChunk",0).put("state","UPLOADING")
                .put("createdAt",store.now().toString()).put("expiresAt",store.now().plusSeconds(24*3600).toString());
        return insert(UPLOAD,UUID.randomUUID(),value);
    }
    ObjectNode get(UUID id) {
        var value=store.find(UPLOAD,id).map(v->((ObjectNode)v).deepCopy()).orElseThrow(ApiFailure::notFound);
        if(!Instant.parse(value.path("expiresAt").asText()).isAfter(store.now()) && !value.path("state").asText().equals("IMPORTED")) throw ApiFailure.conflict();
        return value;
    }
    ObjectNode chunk(UUID id,int index,String encoded) {
        var upload=get(id);int count=upload.path("chunkCount").asInt();
        if(index<0 || index>=count || encoded==null || encoded.length()>4*((CHUNK_BYTES+2)/3)) throw ApiFailure.validation();
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(encoded);if(!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid) {throw ApiFailure.validation();}
        long expected=Math.min(CHUNK_BYTES,upload.path("bytes").asLong()-(long)index*CHUNK_BYTES);
        if(bytes.length!=expected) throw ApiFailure.validation();
        UUID key=chunkId(id,index);var previous=store.find(CHUNK,key);
        if(previous.isPresent()) {
            if(!previous.get().path("uploadId").asText().equals(id.toString()) || !previous.get().path("data").asText().equals(encoded)) throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
            return upload;
        }
        if(!upload.path("state").asText().equals("UPLOADING") || upload.path("nextChunk").asInt()!=index) throw ApiFailure.conflict();
        insert(CHUNK,key,object().put("uploadId",id.toString()).put("index",index).put("data",encoded));
        upload.put("nextChunk",index+1);if(index+1==count) upload.put("state","READY");return update(upload);
    }
    AccountArchive.Download materialize(UUID id) {
        var upload=get(id);if(!Set.of("READY","REVIEWED").contains(upload.path("state").asText())) throw ApiFailure.conflict();
        Path folder=null,file=null;
        try {
            folder=AccountArchive.privateDirectory();file=folder.resolve("input");var digest=MessageDigest.getInstance("SHA-256");long written=0;
            try(var output=new DigestOutputStream(Files.newOutputStream(file),digest)) {
                for(int index=0;index<upload.path("chunkCount").asInt();index++) {
                    var value=store.find(CHUNK,chunkId(id,index)).orElseThrow(ApiFailure::conflict);
                    if(!value.path("uploadId").asText().equals(id.toString()) || value.path("index").asInt(-1)!=index) throw ApiFailure.conflict();
                    byte[] bytes=Base64.getDecoder().decode(value.path("data").asText());written+=bytes.length;
                    if(written>MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");output.write(bytes);
                }
            }
            if(written!=upload.path("bytes").asLong() || !HexFormat.of().formatHex(digest.digest()).equals(upload.path("sha256").asText())) throw ApiFailure.validation();
            return new AccountArchive.Download(file,written);
        }catch(IOException | NoSuchAlgorithmException failure) {
            if(file!=null) new AccountArchive.Download(file,0).close();else if(folder!=null) try {Files.deleteIfExists(folder);}catch(IOException ignored) {}
            throw new ApiFailure(503,"UNAVAILABLE");
        }catch(RuntimeException failure) {if(file!=null) new AccountArchive.Download(file,0).close();throw failure;}
    }
    void remove(UUID id) {
        var upload=get(id);deleteChunks(upload);store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),UPLOAD,id);
    }
    void deleteChunks(ObjectNode upload) {
        UUID id=uuid(upload.get("id"));for(int index=0;index<upload.path("chunkCount").asInt();index++)
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),CHUNK,chunkId(id,index));
    }
    private void cleanup() {
        for(var item:store.list(UPLOAD)) if(!Instant.parse(item.path("expiresAt").asText()).isAfter(store.now())) {
            deleteChunks((ObjectNode)item);store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),UPLOAD,uuid(item.get("id")));
        }
    }
    ObjectNode update(ObjectNode value) {
        if(!store.owner().equals(value.path("ownerId").asText())) throw ApiFailure.notFound();
        var saved=value.deepCopy();long previous=value.path("version").asLong();saved.put("version",Math.addExact(previous,1));
        if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",previous+1,saved.toString(),store.owner(),UPLOAD,uuid(value.get("id")),previous)!=1) throw new ApiFailure(412,"STALE_VERSION");
        return saved;
    }
    private ObjectNode insert(String type,UUID id,ObjectNode value) {
        var saved=value.deepCopy().put("id",id.toString()).put("ownerId",store.owner()).put("version",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,0,?)",store.owner(),type,id,saved.toString());return saved;
    }
    private static UUID chunkId(UUID id,int index) {return ImportLedger.identifier("account-upload-chunk",id.toString(),Integer.toString(index));}
}
