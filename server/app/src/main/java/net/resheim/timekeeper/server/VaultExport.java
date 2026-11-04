package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.query;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.time.Instant;
import java.util.zip.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Portable active Notes files, written inside the shared private export snapshot. */
final class VaultExport {
    static final String MANIFEST=".timekeeper-notes.json",FORMAT="timekeeper-notes-v1";
    static void write(Connection connection,String owner,Path target)throws SQLException,IOException{
        var manifest=object().put("format",FORMAT);var files=manifest.putArray("entries");var names=new java.util.HashSet<String>();
        try(var zip=new ZipOutputStream(Files.newOutputStream(target),StandardCharsets.UTF_8)){
            AccountArchive.records(connection,owner,VaultIndex.ENTRY,entry->{
                if(entry.path("deleted").asBoolean()||entry.path("path").asText().isEmpty())return;
                String path=new VaultPath(entry.path("path").asText()).value();boolean directory=VaultCollection.folder(entry);
                names.add(new VaultPath(path).key());
                var metadata=object().put("path",path).put("kind",directory?"FOLDER":"FILE");
                if(entry.has("createdAt"))metadata.set("createdAt",entry.get("createdAt"));
                final byte[] bytes;
                if(directory){bytes=new byte[0];if(entry.has("modifiedAt"))metadata.set("modifiedAt",entry.get("modifiedAt"));}
                else{
                    var source=source(connection,owner,entry);
                    if(source.path("deleted").asBoolean())throw new IOException("Active Notes entry has no content");
                    bytes=switch(entry.path("sourceType").asText()){
                        case VaultContent.TYPE->VaultContent.bytes(source);
                        case DayNotes.TYPE->DayNotes.document(DayNotes.date(source.path("date").asText()),source);
                        case WeekNotes.TYPE->source.path("body").asText().getBytes(StandardCharsets.UTF_8);
                        case EventNotes.TYPE->EventNotes.document(source);
                        case JournalAssets.TYPE->JournalAssets.bytes(source);
                        default->throw new IOException("Invalid Notes source");
                    };
                    if(source.has("modifiedAt"))metadata.set("modifiedAt",source.get("modifiedAt"));
                    metadata.put("bytes",bytes.length).put("sha256",ArchiveJournalFiles.digest(bytes));
                }
                var item=new ZipEntry(path+(directory?"/":""));
                if(metadata.has("createdAt"))item.setCreationTime(FileTime.from(Instant.parse(metadata.path("createdAt").asText())));
                if(metadata.has("modifiedAt"))item.setLastModifiedTime(FileTime.from(Instant.parse(metadata.path("modifiedAt").asText())));
                zip.putNextEntry(item);zip.write(bytes);zip.closeEntry();files.add(metadata);
            });
            String metadataName=MANIFEST;
            while(names.contains(new VaultPath(metadataName).key()))metadataName=".timekeeper-notes-"+java.util.UUID.randomUUID()+".json";
            zip.putNextEntry(new ZipEntry(metadataName));zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));zip.closeEntry();
        }
    }
    private static ObjectNode source(Connection connection,String owner,ObjectNode entry)throws IOException{
        try(var statement=query(connection,"SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",owner,entry.path("sourceType").asText(),uuid(entry.path("sourceId")));var rows=statement.executeQuery()){
            if(!rows.next())throw new IOException("Missing Notes content");return (ObjectNode)parse(rows.getString(1));
        }catch(SQLException unavailable){throw new IOException(unavailable);}
    }
}
