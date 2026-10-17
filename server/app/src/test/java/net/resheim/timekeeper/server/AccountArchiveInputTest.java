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
        value.putArray("importHistory");return value;
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
        for(String bad:List.of(valid+" {}",valid.replaceFirst("\\{","{\"format\":\"timekeeper-account-v1\","),"null",valid.replace("timekeeper-account-v1","timekeeper-account-v99"))) {
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
}
