package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.query;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Explicit account-data allowlist; neither database dumps nor authentication authority are portable. */
@ApplicationScoped
class AccountArchive {
    static final String FORMAT="timekeeper-account-v3";
    static final String FILE_LEGACY_FORMAT="timekeeper-account-v2";
    static final String LEGACY_FORMAT="timekeeper-account-v1";
    static final Map<String,String> WORK=DomainTransfer.TYPES;
    private static final List<String> HISTORY=List.of("IMPORT_JOB","IMPORT_BATCH","IMPORT_ITEM","IMPORT_MAP","IMPORT_KEY","IMPORT_ORIGIN","IMPORT_UNDO","IMPORT_USE","ARCHIVE_AUDIT","ARCHIVE_IMPORT","CAL_LINK");
    @Inject AgroalDataSource datasource;
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;

    record Download(Path file,long length) implements AutoCloseable {
        @Override public void close() {try {Files.deleteIfExists(file);Files.deleteIfExists(file.getParent());}catch(IOException ignored) {}}
    }
    static Path privateDirectory() throws IOException {
        Path folder=Files.createTempDirectory("timekeeper-account-download-");
        if(Files.getFileStore(folder).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("rwx------"));
        return folder;
    }
    Download download(TokenRegistry.Principal principal) {
        for(int attempt=0;;attempt++) {
            try {return snapshot(principal);}
            catch(RuntimeException failure) {
                if(attempt>=15 || !snapshotConflict(failure)) throw failure;
                // A concurrent authenticated read can update last-seen metadata
                // after this repeatable-read snapshot starts. Retry the complete
                // private export, with fresh authorization, before streaming bytes.
                // Leave enough time for a burst of session refreshes and other
                // exports to finish; a short retry window can starve one export.
                try {Thread.sleep(Math.min(100L,10L*(attempt+1)));}
                catch(InterruptedException stopped) {Thread.currentThread().interrupt();throw failure;}
            }
        }
    }
    private static boolean snapshotConflict(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof SQLException sql && "40001".equals(sql.getSQLState())) return true;
        return false;
    }
    private Download snapshot(TokenRegistry.Principal principal) {
        Path folder=null,file=null;
        try {
            folder=privateDirectory();file=folder.resolve("timekeeper-account.zip");
            try(var connection=datasource.getConnection()) {
                // Account/profile changes do not all acquire the business-owner lock.
                // MVCC gives every file the same snapshot, including management metadata.
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                // Authorization takes row locks so disabling this account waits
                // for an already-authorized download, just as for other reads.
                connection.setAutoCommit(false);
                try {
                    principal=tokens.recheck(principal);
                    if(!principal.permissions().contains("read")) throw new ApiFailure(403,"FORBIDDEN");
                    accounts.guard(connection,principal);
                    write(connection,principal.owner(),file);
                    connection.commit();
                }catch(Throwable failure) {connection.rollback();throw failure;}
            }
            folder.toFile().deleteOnExit();file.toFile().deleteOnExit();
            return new Download(file,Files.size(file));
        }catch(SQLException | IOException failure) {
            if(file!=null) new Download(file,0).close();else if(folder!=null) try {Files.deleteIfExists(folder);}catch(IOException ignored) {}
            throw new ApiFailure(503,"UNAVAILABLE",failure);
        }catch(RuntimeException failure) {
            if(file!=null) new Download(file,0).close();throw failure;
        }
    }
    private void write(Connection c,String owner,Path target) throws SQLException,IOException {
        String archiveId=UUID.randomUUID().toString(),generatedAt=Instant.now().toString();
        ObjectNode counts=object();for(var entry:WORK.entrySet()) counts.put(entry.getKey(),count(c,owner,entry.getValue()));
        long auditCount=0;for(var type:HISTORY) auditCount+=count(c,owner,type);counts.put("importAuditRecords",auditCount);
        var deletedNotes=new HashMap<String,Long>();
        records(c,owner,JournalState.Kind.JOURNAL_DELETE.name(),value->deletedNotes.put(value.path("id").asText(),value.path("noteVersion").asLong()));
        final long[] noteCount={0};records(c,owner,DayNotes.TYPE,value->{if(presentNote(value,deletedNotes))noteCount[0]++;});
        if(noteCount[0]>0)counts.put("dayNotes",noteCount[0]);
        counts.put("dayLabels",count(c,owner,DayLabels.TYPE));
        var deletedWeeks=new HashMap<String,Long>();
        records(c,owner,JournalState.Kind.WEEK_NOTE_DELETE.name(),value->deletedWeeks.put(value.path("id").asText(),value.path("noteVersion").asLong()));
        final long[] weekCount={0};records(c,owner,WeekNotes.TYPE,value->{if(presentWeek(value,deletedWeeks))weekCount[0]++;});
        if(weekCount[0]>0)counts.put("weekNotes",weekCount[0]);
        final long[] assetCount={0};records(c,owner,JournalAssets.TYPE,value->{if(!value.path("deleted").asBoolean())assetCount[0]++;});
        if(assetCount[0]>0)counts.put("journalAssets",assetCount[0]);
        var profile=profile(c,owner);var metadata=metadata(c,owner);
        var manifest=object().put("format",FORMAT).put("archiveId",archiveId).put("generatedAt",generatedAt);
        manifest.set("counts",counts);var files=manifest.putObject("files");
        files.put("archive.json","Private account snapshot and checksummed journal file index. Restore work and selected preferences; account and credential metadata are informational.");
        files.put("YYYY-MM-DD.md","Exact daily Markdown files. Independent day labels are stored in archive.json.");
        files.put("YYYY-Www.md","Exact ISO weekly Markdown files.");
        files.put("assets/*","Binary journal attachments referenced by Markdown.");
        files.put("README.txt","Format, privacy and CSV notes.");
        for(String name:new TreeSet<>(WORK.keySet())) files.put(name+".csv","Spreadsheet-safe "+name+"; exact stored instants and JSON-encoded collection fields.");
        try(var zip=new ZipOutputStream(Files.newOutputStream(target),StandardCharsets.UTF_8)) {
            entry(zip,"archive.json");
            // Closing the JSON writer must not close the surrounding ZIP stream.
            var json=MAPPER.getFactory().createGenerator(zip).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            json.writeStartObject();json.writeStringField("format",FORMAT);json.writeStringField("archiveId",archiveId);json.writeStringField("generatedAt",generatedAt);
            json.writeArrayFieldStart("journalFiles");
            journalFiles(c,owner,deletedNotes,deletedWeeks,(path,bytes)->json.writeTree(ArchiveJournalFiles.metadata(path,bytes)));
            json.writeEndArray();
            json.writeArrayFieldStart("dayLabels");
            records(c,owner,DayLabels.TYPE,value->json.writeTree(object().put("date",value.path("date").asText()).put("text",value.path("text").asText())));
            json.writeEndArray();
            json.writeObjectField("account",metadata);json.writeObjectField("preferences",profile);
            json.writeFieldName("work");json.writeStartObject();json.writeStringField("format","timekeeper-domain-v3");json.writeStringField("zoneId",profile.path("zoneId").asText());
            for(String name:new TreeSet<>(WORK.keySet())) {
                json.writeArrayFieldStart(name);
                records(c,owner,WORK.get(name),value->{var copy=workRecord(name,value);copy.remove(List.of("ownerId","version","state"));json.writeTree(copy);});
                json.writeEndArray();
            }
            json.writeEndObject();json.writeArrayFieldStart("importHistory");
            for(String type:HISTORY) records(c,owner,type,value->{
                var copy=value.deepCopy();copy.remove("ownerId");
                ObjectNode audit=copy;
                if(type.equals("CAL_LINK")){audit=object().put("kind","CALENDAR_ACTIVITY_SOURCE");audit.set("source",copy);}
                json.writeStartObject();json.writeStringField("entityType",type.equals("CAL_LINK")?"ARCHIVE_AUDIT":type);json.writeObjectField("data",audit);json.writeEndObject();
            });
            json.writeEndArray();json.writeEndObject();json.close();zip.closeEntry();
            journalFiles(c,owner,deletedNotes,deletedWeeks,(path,bytes)->{entry(zip,path);zip.write(bytes);zip.closeEntry();});
            entry(zip,"manifest.json");zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));zip.closeEntry();
            for(String name:new TreeSet<>(WORK.keySet())) {
                entry(zip,name+".csv");
                // Include every stored business field, without assuming a fixed frontend schema.
                var columns=new TreeSet<String>();records(c,owner,WORK.get(name),value->columns.addAll(fieldNames(workRecord(name,value))));
                columns.removeAll(List.of("ownerId","state"));var ordered=new ArrayList<>(columns);
                csv(zip,ordered);
                records(c,owner,WORK.get(name),stored->{var value=workRecord(name,stored);var cells=new ArrayList<String>();for(String column:ordered) {
                    JsonNode item=value.get(column);cells.add(item==null || item.isNull() ? "" : item.isTextual() ? item.asText():item.toString());
                }csv(zip,cells);});zip.closeEntry();
            }
            entry(zip,"README.txt");zip.write(("Timekeeper private account archive\n\n"
                +"Keep this ZIP intact for account import. archive.json contains the versioned work snapshot and a journalFiles index with byte lengths and SHA-256 checksums. manifest.json describes record counts and file groups. Day labels are independent metadata in archive.json; Markdown document replacement does not change them.\n"
                +"Daily notes are separate YYYY-MM-DD.md files; weekly notes use ISO names such as 2026-W44.md. Exact Markdown and frontmatter are preserved. Binary attachments are under assets/, so relative Markdown links remain usable after extraction.\n"
                +"This ZIP is not encrypted. Protect downloads and temporary files on encrypted private storage; see the journal storage and key-management deployment requirements.\n"
                +"All records belong to the signed-in account. Administrator access does not include another account's data.\n"
                +"Account identity, recovery addresses and credential-management metadata are informational and are not restored.\n"
                +"Import history is an audit copy; it cannot replay an import or authorize Undo in another account.\n"
                +"Passwords, credential material, token/session secrets, recovery links and global service/deployment settings are excluded.\n"
                +"CSV files use UTF-8, quoted cells and exact stored timestamps. Arrays/objects use JSON. Potential spreadsheet formulas are prefixed with an apostrophe; JSON retains the original values.\n")
                .getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
    @FunctionalInterface private interface JournalFile {void accept(String path,byte[] bytes) throws IOException;}
    private static void journalFiles(Connection c,String owner,Map<String,Long> deletedNotes,Map<String,Long> deletedWeeks,JournalFile output) throws SQLException,IOException {
        records(c,owner,DayNotes.TYPE,value->{if(presentNote(value,deletedNotes)){
            String path=value.path("date").asText()+".md";
            byte[] bytes=value.has("markdown")?value.path("markdown").asText().getBytes(StandardCharsets.UTF_8)
                    :JournalMarkdown.render(path,null,value.path("text").asText(),value.path("body").asText());
            output.accept(path,bytes);
        }});
        records(c,owner,WeekNotes.TYPE,value->{if(presentWeek(value,deletedWeeks))output.accept(value.path("week").asText()+".md",value.path("body").asText().getBytes(StandardCharsets.UTF_8));});
        records(c,owner,JournalAssets.TYPE,value->{if(!value.path("deleted").asBoolean())output.accept("assets/"+value.path("filename").asText(),JournalAssets.bytes(value));});
        records(c,owner,"TASK_ATTACHMENT",value->output.accept(ArchiveJournalFiles.taskPath(value.path("id").asText()),Base64.getDecoder().decode(value.path("data").asText())));
    }
    private static ObjectNode workRecord(String name,ObjectNode value){
        var result=value.deepCopy();if(name.equals("taskAttachments")){result.remove("data");result.put("archiveFile",ArchiveJournalFiles.taskPath(value.path("id").asText()));}return result;
    }
    private static void entry(ZipOutputStream zip,String name) throws IOException {zip.putNextEntry(new ZipEntry(name));}
    private static boolean presentNote(ObjectNode value,Map<String,Long> deleted){
        return !(deleted.getOrDefault(value.path("id").asText(),-1L)==value.path("version").asLong()&&DayNotes.emptyForDeletion(value));
    }
    private static boolean presentWeek(ObjectNode value,Map<String,Long> deleted){
        return !(deleted.getOrDefault(value.path("id").asText(),-1L)==value.path("version").asLong()&&value.path("body").asText().isEmpty());
    }
    private static Set<String> fieldNames(ObjectNode value) {var names=new HashSet<String>();value.fieldNames().forEachRemaining(names::add);return names;}
    static String csvCell(String value) {
        int offset=0;
        while(offset<value.length()) {
            int code=value.codePointAt(offset);
            if(!Character.isWhitespace(code) && !Character.isSpaceChar(code) && !Character.isISOControl(code) && Character.getType(code)!=Character.FORMAT) break;
            offset+=Character.charCount(code);
        }
        if(offset<value.length() && "=+-@".indexOf(value.charAt(offset))>=0 || !value.isEmpty() && Character.isISOControl(value.codePointAt(0))) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static void csv(OutputStream stream,List<String> cells) throws IOException {
        stream.write((String.join(",",cells.stream().map(AccountArchive::csvCell).toList())+"\r\n").getBytes(StandardCharsets.UTF_8));
    }
    @FunctionalInterface private interface Row {void accept(ObjectNode value) throws IOException;}
    private static void records(Connection c,String owner,String type,Row row) throws SQLException,IOException {
        try(var statement=query(c,"SELECT id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type=? ORDER BY id",owner,type)) {
            statement.setFetchSize(256);
            try(var rows=statement.executeQuery()) {while(rows.next()) {
                var value=(ObjectNode)parse(rows.getString(3));
                if(!owner.equals(value.path("ownerId").asText()) || !rows.getObject(1).toString().equals(value.path("id").asText()) || rows.getLong(2)!=value.path("version").asLong(-1)) throw new ApiFailure(503,"UNAVAILABLE");
                if(type.equals("ACTIVITY")) value.remove("workSessionId");row.accept(value);
            }}
        }
    }
    private static long count(Connection c,String owner,String type) throws SQLException {
        try(var statement=query(c,"SELECT COUNT(*) FROM tk_resource WHERE owner_id=? AND entity_type=?",owner,type);var rows=statement.executeQuery()) {rows.next();return rows.getLong(1);}
    }
    private static ObjectNode profile(Connection c,String owner) throws SQLException {
        try(var statement=query(c,"SELECT zone_id FROM tk_owner WHERE id=?",owner);var rows=statement.executeQuery()) {
            if(!rows.next()) throw ApiFailure.notFound();return object().put("zoneId",rows.getString(1));
        }
    }
    private static ObjectNode metadata(Connection c,String owner) throws SQLException {
        var result=AccountStore.account(c,owner,false);var addresses=result.putArray("recoveryAddresses");
        try(var statement=query(c,"SELECT slot,verified_address,pending_address,version FROM tk_recovery_address WHERE owner_id=? ORDER BY slot",owner);var rows=statement.executeQuery()) {
            while(rows.next()) addresses.add(object().put("slot",rows.getString(1)).put("verifiedAddress",rows.getString(2)).put("pendingAddress",rows.getString(3)).put("version",rows.getLong(4)));
        }
        var tokens=result.putArray("apiTokenMetadata");
        try(var statement=query(c,"SELECT name,permissions,expires_at,revoked,source,created_at,last_used_at FROM tk_api_token WHERE owner_id=? ORDER BY created_at,id",owner);var rows=statement.executeQuery()) {
            while(rows.next()) tokens.add(object().put("name",rows.getString(1)).put("permissions",rows.getString(2)).put("expiresAt",rows.getString(3)).put("revoked",rows.getBoolean(4)).put("source",rows.getString(5)).put("createdAt",rows.getString(6)).put("lastUsedAt",rows.getString(7)));
        }
        var passkeys=result.putArray("passkeyMetadata");
        try(var statement=query(c,"SELECT name,rp_id,created_at,last_used_at,revoked FROM tk_passkey_credential WHERE owner_id=? ORDER BY created_at,id",owner);var rows=statement.executeQuery()) {
            while(rows.next()) passkeys.add(object().put("name",rows.getString(1)).put("rpId",rows.getString(2)).put("createdAt",rows.getString(3)).put("lastUsedAt",rows.getString(4)).put("revoked",rows.getBoolean(5)));
        }
        return result;
    }
}
