package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** No ZIP extraction, permissive JSON recovery or trust in archive-selected ownership. */
final class AccountArchiveInput {
    private static final Set<String> FIELDS=Set.of("format","archiveId","generatedAt","account","preferences","work","importHistory");
    private static final Set<String> FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv");
    private static final ObjectMapper READER=reader();
    private static ObjectMapper reader() {
        var mapper=new ObjectMapper();mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1024*1024).maxNumberLength(32).build());
        return mapper;
    }
    record Verified(ObjectNode data,String sha256) { }
    static ObjectNode read(Path file,String mediaType) {return readVerified(file,mediaType).data();}
    static Verified readVerified(Path file,String mediaType) {
        try {
            if(Files.size(file)>AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            if(mediaType.equals("application/json")) try(var input=Files.newInputStream(file)) {return verified(input);}
            if(!mediaType.equals("application/zip")) throw ApiFailure.validation();
            try(var zip=new ZipFile(file.toFile())) {
                var seen=new HashSet<String>();var entries=zip.entries();long declared=0;
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();
                    if(entry.isDirectory() || !FILES.contains(entry.getName()) || !seen.add(entry.getName())) throw ApiFailure.validation();
                    if(entry.getSize()<0 || entry.getSize()>AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                    declared+=entry.getSize();if(declared>3*AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                }
                if(!seen.equals(FILES)) throw ApiFailure.validation();
                Verified verified;
                try(var input=zip.getInputStream(zip.getEntry("archive.json"))) {verified=verified(input);}
                var archive=verified.data();
                try(var input=zip.getInputStream(zip.getEntry("manifest.json"))) {
                    JsonNode manifest=json(input,64*1024);
                    for(String name:List.of("format","archiveId","generatedAt")) if(!archive.path(name).equals(manifest.path(name))) throw ApiFailure.validation();
                    if(!counts(archive).equals(manifest.path("counts"))) throw ApiFailure.validation();
                }
                return verified;
            }
        }catch(JsonProcessingException | ZipException | EOFException invalid) {throw ApiFailure.validation();}
        catch(IOException unavailable) {throw new ApiFailure(503,"UNAVAILABLE");}
    }
    private static Verified verified(InputStream input) throws IOException {
        try {var digest=MessageDigest.getInstance("SHA-256");var value=document(new DigestInputStream(input,digest));return new Verified(value,HexFormat.of().formatHex(digest.digest()));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static ObjectNode document(InputStream input) throws IOException {
        JsonNode value=json(input,AccountUploads.MAX_BYTES);
        if(!value.isObject() || !names(value).equals(FIELDS) || !value.path("format").asText().equals(AccountArchive.FORMAT)) throw ApiFailure.validation();
        try {ContractInputs.pathId(value.path("archiveId").asText());Instant.parse(value.path("generatedAt").asText());}
        catch(RuntimeException invalid) {throw ApiFailure.validation();}
        if(!value.path("account").isObject() || !value.path("preferences").isObject() || !value.path("work").isObject() || !value.path("importHistory").isArray()) throw ApiFailure.validation();
        if(!names(value.path("preferences")).equals(Set.of("zoneId")) || !value.path("preferences").path("zoneId").isTextual()) throw ApiFailure.validation();
        ContractInputs.zone(value.path("preferences").path("zoneId").asText());
        if(!value.path("preferences").path("zoneId").equals(value.path("work").path("zoneId"))) throw ApiFailure.validation();
        // Business and metadata JSON schemas plus graph validation are applied
        // before preview or commit; this layer only establishes safe framing.
        return (ObjectNode)value;
    }
    static ObjectNode counts(JsonNode archive) {
        var result=object();for(String name:AccountArchive.WORK.keySet()) {
            if(!archive.path("work").path(name).isArray()) throw ApiFailure.validation();result.put(name,archive.path("work").path(name).size());
        }
        result.put("importAuditRecords",archive.path("importHistory").size());return result;
    }
    private static Set<String> names(JsonNode value) {var result=new HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
    private static JsonNode json(InputStream input,long limit) throws IOException {
        var bounded=new FilterInputStream(input) {
            long consumed;
            private int count(int bytes) {if(bytes>0 && (consumed+=bytes)>limit) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");return bytes;}
            @Override public int read() throws IOException {int value=super.read();if(value!=-1) count(1);return value;}
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {return count(in.read(bytes,offset,length));}
        };
        JsonNode value=READER.readTree(bounded);if(value==null) throw ApiFailure.validation();return value;
    }
}
