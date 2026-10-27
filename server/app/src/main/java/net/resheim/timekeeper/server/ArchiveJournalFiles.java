package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded journal ZIP entries; paths never become filesystem extraction targets. */
final class ArchiveJournalFiles {
    enum Kind { DAY, WEEK, ASSET, TASK_ASSET }
    // The space cannot occur in a journal asset's filename, avoiding a file /
    // directory collision while preserving every existing Markdown asset link.
    static String taskPath(String id){return "assets/task attachments/"+ContractInputs.pathId(id)+".bin";}
    static Kind kind(String path){
        try{
            if(path!=null&&path.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")){JournalMarkdown.date(path);return Kind.DAY;}
            if(path!=null&&path.matches("[0-9]{4}-W[0-9]{2}\\.md")){WeekNotes.filename(path);return Kind.WEEK;}
            if(path!=null&&path.matches("assets/task attachments/[a-f0-9-]{36}\\.bin")){if(!taskPath(path.substring("assets/task attachments/".length(),path.length()-4)).equals(path))throw ApiFailure.validation();return Kind.TASK_ASSET;}
            if(path!=null&&path.startsWith("assets/")){JournalAssets.name(path.substring(7));return Kind.ASSET;}
        }catch(RuntimeException invalid){throw ApiFailure.validation();}
        throw ApiFailure.validation();
    }
    static int limit(String path){return switch(kind(path)){case DAY->JournalMarkdown.FILE_BYTES;case WEEK->DayNotes.BODY_BYTES;case ASSET->JournalAssets.MAX_BYTES;case TASK_ASSET->net.resheim.eclipse.timekeeper.domain.TaskAttachment.MAX_BYTES;};}
    static String digest(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static ObjectNode metadata(String path,byte[] bytes){return object().put("path",path).put("bytes",bytes.length).put("sha256",digest(bytes));}
    static Set<String> verify(ZipFile zip,JsonNode files,JsonNode taskAttachments) throws IOException {
        if(!files.isArray()||files.size()>760000)throw ApiFailure.validation();
        var seen=new HashSet<String>();var taskFiles=new HashMap<String,JsonNode>();int days=0,weeks=0,assets=0;
        for(var file:files){
            if(!file.isObject()||file.size()!=3||!file.path("path").isTextual()||!file.path("bytes").isIntegralNumber()||!file.path("bytes").canConvertToLong()
                    ||!file.path("sha256").isTextual()||!file.path("sha256").asText().matches("[a-f0-9]{64}"))throw ApiFailure.validation();
            String path=file.path("path").asText();if(!seen.add(path))throw ApiFailure.validation();
            switch(kind(path)){case DAY->days++;case WEEK->weeks++;case ASSET->assets++;case TASK_ASSET->taskFiles.put(path,file);}
            if(days>250000||weeks>250000||assets>10000||taskFiles.size()>250000)throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            byte[] bytes=read(zip,file);validate(path,bytes);
        }
        for(var attachment:taskAttachments){
            String path=taskPath(attachment.path("id").asText());var file=taskFiles.remove(path);
            if(file==null||!path.equals(attachment.path("archiveFile").asText())||attachment.has("data")
                    ||!file.path("bytes").equals(attachment.path("bytes"))||!file.path("sha256").equals(attachment.path("sha256")))throw ApiFailure.validation();
        }
        if(!taskFiles.isEmpty())throw ApiFailure.validation();
        return seen;
    }
    private static byte[] read(ZipFile zip,JsonNode file) throws IOException {
        String path=file.path("path").asText();long size=file.path("bytes").asLong(-1);int limit=limit(path);
        if(size<0||size>limit)throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        var entry=zip.getEntry(path);if(entry==null||entry.isDirectory()||entry.getSize()!=size)throw ApiFailure.validation();
        try(var input=zip.getInputStream(entry)){
            byte[] bytes=input.readNBytes(limit+1);
            if(bytes.length!=size||!digest(bytes).equals(file.path("sha256").asText()))throw ApiFailure.validation();
            return bytes;
        }
    }
    private static String utf8(byte[] bytes){
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw ApiFailure.validation();}
    }
    private static void validate(String path,byte[] bytes){
        try{switch(kind(path)){case DAY->JournalMarkdown.parse(path,bytes);case WEEK->utf8(bytes);case ASSET,TASK_ASSET->{}}}
        catch(JournalMarkdown.Invalid invalid){throw ApiFailure.validation();}
    }
    static void restore(Path archive,JsonNode files,ServerStore store){
        try(var zip=new ZipFile(archive.toFile())){
            for(var file:files){
                String path=file.path("path").asText();if(kind(path)==Kind.TASK_ASSET)continue;byte[] bytes=read(zip,file);
                switch(kind(path)){
                    case DAY->{
                        var parsed=JournalMarkdown.parse(path,bytes);
                        new DayNotes(store).restore(array().add(object().put("date",parsed.date().toString()).put("text",parsed.label()).put("body",parsed.body()).put("markdown",utf8(bytes))));
                    }
                    case WEEK->new WeekNotes(store).restore(array().add(object().put("week",path.substring(0,8)).put("filename",path).put("markdown",utf8(bytes))));
                    case ASSET->new JournalAssets(store).restore(array().add(object().put("filename",path.substring(7)).put("data",Base64.getEncoder().encodeToString(bytes))));
                    case TASK_ASSET->{} // Imported with the owned task graph and remapped task ID.
                }
            }
        }catch(JournalMarkdown.Invalid invalid){throw ApiFailure.validation();}
        catch(IOException unavailable){throw new ApiFailure(503,"UNAVAILABLE");}
    }
    static final class TaskReader implements AutoCloseable {
        private final ZipFile zip;private final Map<String,JsonNode> files=new HashMap<>();
        TaskReader(Path path,JsonNode entries){
            try{zip=new ZipFile(path.toFile());}catch(IOException unavailable){throw new ApiFailure(503,"UNAVAILABLE");}
            for(var entry:entries)if(kind(entry.path("path").asText())==Kind.TASK_ASSET)files.put(entry.path("path").asText(),entry);
        }
        String data(JsonNode attachment){
            var file=files.get(attachment.path("archiveFile").asText());if(file==null)throw ApiFailure.validation();
            try{return Base64.getEncoder().encodeToString(read(zip,file));}catch(IOException unavailable){throw new ApiFailure(503,"UNAVAILABLE");}
        }
        public void close(){try{zip.close();}catch(IOException ignored){}}
    }
}
