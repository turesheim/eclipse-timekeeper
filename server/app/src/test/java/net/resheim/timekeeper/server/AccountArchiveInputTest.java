package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountArchiveInputTest {
    @TempDir Path temporary;
    private ObjectNode document() {
        var value=object().put("format",AccountArchive.FORMAT).put("archiveId",UUID.randomUUID().toString()).put("generatedAt","2026-10-17T00:00:00Z");
        value.putObject("account").put("username","informational");value.putObject("preferences").put("zoneId","Europe/Oslo");
        var work=value.putObject("work").put("format","timekeeper-domain-v3").put("zoneId","Europe/Oslo");for(String name:AccountArchive.WORK.keySet()) work.putArray(name);
        value.putArray("importHistory");value.putArray("journalFiles");return value;
    }
    private Path json(String value) throws Exception {return Files.writeString(temporary.resolve(UUID.randomUUID()+".json"),value);}
    private Path zip(ObjectNode value,boolean wrongCount,String extra) throws Exception {
        var manifest=object();for(String name:List.of("format","archiveId","generatedAt")) manifest.set(name,value.get(name));
        var counts=AccountArchiveInput.counts(value);if(wrongCount) counts.put("tasks",1);manifest.set("counts",counts);
        var files=new LinkedHashMap<String,String>();files.put("archive.json",value.toString());files.put("manifest.json",manifest.toString());files.put("README.txt","Private account");
        for(String name:AccountArchive.WORK.keySet()) files.put(name+".csv","id\r\n");if(extra!=null) files.put(extra,"untrusted");
        Path target=temporary.resolve(UUID.randomUUID()+".zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(target))) {for(var entry:files.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return target;
    }
    @Test void extractedJsonAndCompleteZipHaveTheSameStrictFraming() throws Exception {
        var value=document();assertEquals(value,AccountArchiveInput.read(json(value.toString()),"application/json"));
        assertEquals(value,AccountArchiveInput.read(zip(value,false,null),"application/zip"));
    }
    @Test void duplicateKeysTrailingDocumentsUnknownVersionsAndUnsafeZipEntriesAreRejected() throws Exception {
        var value=document();String valid=value.toString();
        for(String bad:List.of(valid+" {}",valid.replaceFirst("\\{","{\"format\":\"timekeeper-account-v1\","),"null",valid.replace(AccountArchive.FORMAT,"timekeeper-account-v99"))) {
            Path file=json(bad);assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(file,"application/json")).status);
        }
        for(String name:List.of("../outside","/absolute","nested/archive.json","__MACOSX/extra")) {
            Path file=zip(value,false,name);assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(file,"application/zip")).status);
        }
        Path wrong=zip(value,true,null);assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(wrong,"application/zip")).status);
        Path fake=json(valid);assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fake,"application/zip")).status);
        value.putObject("preferences").put("zoneId","UTC");Path mismatched=json(value.toString());
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(mismatched,"application/json")).status);
    }
    private Path fileArchive(ObjectNode value,Map<String,byte[]> payloads) throws Exception {
        var manifest=object();for(String name:List.of("format","archiveId","generatedAt"))manifest.set(name,value.get(name));manifest.set("counts",AccountArchiveInput.counts(value));
        Path path=temporary.resolve(UUID.randomUUID()+".zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))){
            var files=new LinkedHashMap<String,byte[]>();files.put("archive.json",value.toString().getBytes(StandardCharsets.UTF_8));files.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));files.put("README.txt",new byte[0]);
            for(String name:AccountArchive.WORK.keySet())files.put(name+".csv",new byte[0]);files.putAll(payloads);
            for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return path;
    }
    @Test void separateMarkdownAndAssetsRequireExactIndexedFilesAndCompleteZip() throws Exception {
        var value=document();var files=new LinkedHashMap<String,byte[]>();
        files.put("2026-10-27.md","---\r\ncustom: metadata\r\n---\r\n# Day\r\n".getBytes(StandardCharsets.UTF_8));
        files.put("2026-W44.md","# Week\n".getBytes(StandardCharsets.UTF_8));files.put("assets/photo.bin",new byte[]{0,1,(byte)255});
        files.forEach((path,bytes)->value.withArray("journalFiles").add(ArchiveJournalFiles.metadata(path,bytes)));
        assertEquals(value,AccountArchiveInput.read(fileArchive(value,files),"application/zip"));
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(json(value.toString()),"application/json")).status);
        var missing=new LinkedHashMap<>(files);missing.remove("assets/photo.bin");
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(value,missing),"application/zip")).status);
        var changed=new LinkedHashMap<>(files);changed.put("2026-W44.md","# Edit\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(value,changed),"application/zip")).status);
        var extra=new LinkedHashMap<>(files);extra.put("2026-10-28.md",new byte[0]);
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(value,extra),"application/zip")).status);
        var duplicate=value.deepCopy();duplicate.withArray("journalFiles").add(duplicate.path("journalFiles").get(0).deepCopy());
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(duplicate,files),"application/zip")).status);
        var invalid=document();byte[] invalidUtf8={(byte)0xc3,0x28};invalid.withArray("journalFiles").add(ArchiveJournalFiles.metadata("2026-W44.md",invalidUtf8));
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(invalid,Map.of("2026-W44.md",invalidUtf8)),"application/zip")).status);
    }
    @Test void legacyInlineJsonAndZipRetainTheSameRestoreIdentity() throws Exception {
        var value=document().put("format",AccountArchive.LEGACY_FORMAT);value.remove("journalFiles");value.putArray("dayNotes").add(object().put("date","2026-10-27").put("text","Legacy"));
        var plain=AccountArchiveInput.readVerified(json(value.toString()),"application/json");var packed=AccountArchiveInput.readVerified(zip(value,false,null),"application/zip");
        assertEquals(plain.data(),packed.data());assertEquals(plain.sha256(),packed.sha256());
    }
    @Test void taskAttachmentFileReferencesMustMatchTheirIndexedBytesAndChecksum() throws Exception {
        var value=document();String id=UUID.randomUUID().toString(),path=ArchiveJournalFiles.taskPath(id);byte[] bytes={1,2,3};
        value.withArray("journalFiles").add(ArchiveJournalFiles.metadata(path,bytes));
        ((ObjectNode)value.path("work")).withArray("taskAttachments").add(object().put("id",id).put("taskId",UUID.randomUUID().toString()).put("fileName","original.bin").put("contentType","application/octet-stream").put("bytes",bytes.length).put("sha256",ArchiveJournalFiles.digest(bytes)).put("archiveFile",path));
        assertEquals(value,AccountArchiveInput.read(fileArchive(value,Map.of(path,bytes)),"application/zip"));
        var mismatch=value.deepCopy();((ObjectNode)mismatch.path("work").path("taskAttachments").get(0)).put("sha256","b".repeat(64));
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(mismatch,Map.of(path,bytes)),"application/zip")).status);
        var unclaimed=value.deepCopy();((ObjectNode)unclaimed.path("work")).putArray("taskAttachments");
        assertEquals(422,assertThrows(ApiFailure.class,()->AccountArchiveInput.read(fileArchive(unclaimed,Map.of(path,bytes)),"application/zip")).status);
    }
}
