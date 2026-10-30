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
    private static final Set<String> LEGACY_FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv");
    private static final Set<String> PREVIOUS_FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv","taskConnections.csv","taskLists.csv");
    private static final Set<String> TOMBSTONE_FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv","taskConnections.csv","taskLists.csv","taskTombstones.csv");
    private static final Set<String> SECTION_FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv","taskConnections.csv","taskLists.csv","taskListSections.csv","taskTombstones.csv");
    private static final Set<String> FILES=Set.of("archive.json","manifest.json","README.txt","clients.csv","projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv","taskConnections.csv","taskLists.csv","taskListSections.csv","taskTombstones.csv","taskAttachments.csv");
    private static final ObjectMapper READER=reader();
    private static ObjectMapper reader() {
        var mapper=new ObjectMapper();mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(7*1024*1024).maxNumberLength(32).build());
        return mapper;
    }
    record Verified(ObjectNode data,String sha256) { }
    static ObjectNode read(Path file,String mediaType) {return readVerified(file,mediaType).data();}
    static Verified readVerified(Path file,String mediaType) {
        try {
            if(Files.size(file)>AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            if(mediaType.equals("application/json")) try(var input=Files.newInputStream(file)) {
                var result=verified(input);if(result.data().path("journalFiles").size()>0||result.data().path("vault").path("entries").size()>0||result.data().path("vault").path("files").size()>0||!result.data().path("format").asText().equals(AccountArchive.LEGACY_FORMAT)&&result.data().path("work").path("taskAttachments").size()>0)throw ApiFailure.validation();return result;
            }
            if(!mediaType.equals("application/zip")) throw ApiFailure.validation();
            try(var zip=new ZipFile(file.toFile())) {
                var seen=new HashSet<String>();var journalPaths=new HashSet<String>();var vaultPaths=new HashSet<String>();var entries=zip.entries();long declared=0;
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();
                    if(entry.isDirectory() || !seen.add(entry.getName())) throw ApiFailure.validation();
                    if(!FILES.contains(entry.getName())){if(VaultArchive.owns(entry.getName()))vaultPaths.add(entry.getName());else{ArchiveJournalFiles.kind(entry.getName());journalPaths.add(entry.getName());}}
                    if(seen.size()>1010000+FILES.size())throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                    if(entry.getSize()<0 || entry.getSize()>AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                    declared+=entry.getSize();if(declared>3*AccountUploads.MAX_BYTES) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                }
                var rootPaths=new HashSet<>(seen);rootPaths.removeAll(journalPaths);rootPaths.removeAll(vaultPaths);
                if(!rootPaths.equals(FILES) && !rootPaths.equals(SECTION_FILES) && !rootPaths.equals(TOMBSTONE_FILES) && !rootPaths.equals(PREVIOUS_FILES) && !rootPaths.equals(LEGACY_FILES)) throw ApiFailure.validation();
                Verified verified;
                try(var input=zip.getInputStream(zip.getEntry("archive.json"))) {verified=verified(input);}
                var archive=verified.data();
                if(archive.path("format").asText().equals(AccountArchive.FORMAT)){if(!VaultArchive.verify(zip,archive.path("vault")).equals(vaultPaths))throw ApiFailure.validation();}
                else if(!vaultPaths.isEmpty())throw ApiFailure.validation();
                if(!archive.path("format").asText().equals(AccountArchive.LEGACY_FORMAT)){
                    if(!rootPaths.equals(FILES)||!ArchiveJournalFiles.verify(zip,archive.path("journalFiles"),archive.path("work").path("taskAttachments")).equals(journalPaths))throw ApiFailure.validation();
                }else if(!journalPaths.isEmpty())throw ApiFailure.validation();
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
        JsonNode value=json(input,AccountUploads.MAX_JSON_BYTES);
        if(!value.isObject() || !fields(value).equals(FIELDS) || !Set.of(AccountArchive.FORMAT,AccountArchive.LABEL_LEGACY_FORMAT,AccountArchive.FILE_LEGACY_FORMAT,AccountArchive.LEGACY_FORMAT).contains(value.path("format").asText())) throw ApiFailure.validation();
        if(!value.path("format").asText().equals(AccountArchive.LEGACY_FORMAT)){
            if(!value.path("journalFiles").isArray()||value.has("dayNotes")||value.has("weekNotes")||value.has("journalAssets"))throw ApiFailure.validation();
        }else if(value.has("journalFiles"))throw ApiFailure.validation();
        boolean labels=Set.of(AccountArchive.FORMAT,AccountArchive.LABEL_LEGACY_FORMAT).contains(value.path("format").asText());
        if(labels&&!value.path("dayLabels").isArray()||!labels&&value.has("dayLabels"))throw ApiFailure.validation();
        if(value.path("format").asText().equals(AccountArchive.FORMAT)){if(!value.path("vault").isObject())throw ApiFailure.validation();}
        else if(value.has("vault"))throw ApiFailure.validation();
        try {ContractInputs.pathId(value.path("archiveId").asText());Instant.parse(value.path("generatedAt").asText());}
        catch(RuntimeException invalid) {throw ApiFailure.validation();}
        if(!value.path("account").isObject() || !value.path("preferences").isObject() || !value.path("work").isObject() || !value.path("importHistory").isArray()) throw ApiFailure.validation();
        if(!Set.of("zoneId","focusAreas").containsAll(names(value.path("preferences"))) || !value.path("preferences").path("zoneId").isTextual()) throw ApiFailure.validation();
        ContractInputs.zone(value.path("preferences").path("zoneId").asText());
        if(!value.path("preferences").path("zoneId").equals(value.path("work").path("zoneId"))) throw ApiFailure.validation();
        // Business and metadata JSON schemas plus graph validation are applied
        // before preview or commit; this layer only establishes safe framing.
        return (ObjectNode)value;
    }
    private static Set<String> fields(JsonNode value) {var fields=names(value);fields.removeAll(Set.of("dayNotes","weekNotes","journalAssets","journalFiles","dayLabels","vault"));return fields;}
    static ObjectNode counts(JsonNode archive) {
        var result=object();for(String name:AccountArchive.WORK.keySet()) {
            var values=archive.path("work").path(name);
            if(values.isMissingNode() && Set.of("taskConnections","taskLists","taskListSections","taskTombstones","taskAttachments").contains(name)) continue;
            if(!values.isArray()) throw ApiFailure.validation();result.put(name,values.size());
        }
        result.put("importAuditRecords",archive.path("importHistory").size());
        if(archive.has("dayLabels"))result.put("dayLabels",archive.path("dayLabels").size());
        if(archive.has("vault")){result.put("notesEntries",archive.path("vault").path("entries").size());result.put("notesFiles",archive.path("vault").path("files").size());}
        if(archive.has("dayNotes"))result.put("dayNotes",archive.path("dayNotes").size());
        if(archive.has("weekNotes"))result.put("weekNotes",archive.path("weekNotes").size());
        if(archive.has("journalAssets"))result.put("journalAssets",archive.path("journalAssets").size());
        if(archive.has("journalFiles")){
            int days=0,weeks=0,events=0,assets=0;for(var file:archive.path("journalFiles"))switch(ArchiveJournalFiles.kind(file.path("path").asText())){case DAY->days++;case WEEK->weeks++;case EVENT->events++;case ASSET->assets++;case TASK_ASSET->{}}
            if(days>0)result.put("dayNotes",days);if(weeks>0)result.put("weekNotes",weeks);if(events>0)result.put("eventNotes",events);if(assets>0)result.put("journalAssets",assets);
        }
        return result;
    }
    static ObjectNode relationships(JsonNode archive) {
        var result=object();var work=archive.path("work");
        String[][] links={{"projectClients","projects","clientId"},{"listConnections","taskLists","connectionId"},{"sectionLists","taskListSections","taskListId"},{"attachmentTasks","taskAttachments","taskId"},{"taskProjects","tasks","projectId"},
                {"parentTasks","tasks","parentTaskId"},{"activityTasks","activities","taskId"},
                {"activityProjects","activities","projectId"},{"activityLabels","activities","labelIds"},
                {"workSessionClients","workSessions","clientId"},{"workSessionProjects","workSessions","projectId"},
                {"workSessionLabels","workSessions","labelIds"}};
        for(var link:links) {
            long count=0;for(var record:work.path(link[1])) {var value=record.path(link[2]);count+=value.isArray() ? value.size():value.isTextual() ? 1:0;}
            result.put(link[0],count);
        }
        long taskLists=0;for(var task:work.path("tasks")) for(var reference:task.path("externalReferences")) if(reference.path("taskListId").isTextual()) taskLists++;
        result.put("externalReferenceLists",taskLists);
        return result;
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
