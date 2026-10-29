package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Complete encrypted envelope; source paths remain metadata, never extraction targets. */
final class InstanceBackupArchive {
    private InstanceBackupArchive() { }
    static final String FORMAT="timekeeper-instance-v1";
    private static final int MAX_CONFIGURATION=4_000_000,MAX_MANIFEST=1_000_000;
    private static final Set<String> SOURCE_FIELDS=Set.of("serverVersion","commit","databaseType","packageId","publicOrigin");
    private static final ObjectReader READER=MAPPER.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Byte/structure verification is not authorization or destination readiness. */
    record Verified(Path directory,Path database,ObjectNode manifest,InstanceConfiguration.Snapshot configuration,Map<String,Long> counts) implements AutoCloseable {
        @Override public String toString(){return "Verified private instance backup";}
        @Override public void close() throws IOException {configuration.close();remove(directory);}
    }
    /** Caller owns the encrypted result and the input configuration snapshot. */
    static Path create(Connection snapshot,InstanceConfiguration.Snapshot configuration,ObjectNode source,char[] passphrase,Path privateRoot) throws IOException {
        Path stage=null,result=null;boolean complete=false;
        try {
            validateSource(source);InstanceConfiguration.validate(configuration.document(),configuration.files().keySet());
            String backend=snapshot.getMetaData().getDatabaseProductName().equals("PostgreSQL")?"postgresql":"h2";
            if(!backend.equals(source.path("databaseType").asText())||!backend.equals(configuration.document().path("properties").path("quarkus.datasource.db-kind").asText()))throw invalid();
            stage=privateDirectory(privateRoot);Path plain=privateFile(stage,".zip");
            var manifest=object().put("format",FORMAT).put("archiveId",UUID.randomUUID().toString()).put("schema",ServerSchema.CURRENT).put("createdAt",Instant.now().toString());
            manifest.set("source",source.deepCopy());var files=manifest.putObject("files");
            try(var zip=new ZipOutputStream(Files.newOutputStream(plain))) {
                entry(zip,"database.json",files,output->InstanceDatabaseImage.write(snapshot,output));
                byte[] config=MAPPER.writeValueAsBytes(configuration.document());if(config.length>MAX_CONFIGURATION)throw invalid();
                try {entry(zip,"configuration.json",files,output->output.write(config));}finally{Arrays.fill(config,(byte)0);}
                long total=0;
                for(var file:new TreeMap<>(configuration.files()).entrySet()) {
                    if(file.getValue().length>InstanceConfiguration.MAX_FILE_BYTES||(total+=file.getValue().length)>InstanceConfiguration.MAX_TOTAL_BYTES)throw invalid();
                    entry(zip,file.getKey(),files,output->output.write(file.getValue()));
                }
                byte[] metadata=MAPPER.writeValueAsBytes(manifest);if(metadata.length>MAX_MANIFEST)throw invalid();
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(metadata);zip.closeEntry();
            }
            result=InstanceBackupEncryption.encrypt(plain,privateRoot,passphrase);remove(stage);stage=null;complete=true;return result;
        }catch(Exception failure){throw new IOException("INSTANCE_BACKUP_CREATION_FAILED");}
        finally {if(stage!=null)remove(stage);if(!complete&&result!=null)try{Files.deleteIfExists(result);}catch(IOException failure){throw new IOException("INSTANCE_BACKUP_CLEANUP_FAILED");}}
    }

    /** Fully authenticate, verify every indexed byte, and preflight the database. */
    static Verified open(Path encrypted,char[] passphrase,Path privateRoot,long maximumBytes) throws IOException {
        Path stage=privateDirectory(privateRoot);boolean complete=false;
        var contents=new LinkedHashMap<String,byte[]>();
        try {
            Path plain=InstanceBackupEncryption.decrypt(encrypted,stage,passphrase,maximumBytes);Path database=privateFile(stage,".database.json");
            ObjectNode manifest;ObjectNode configuration;
            try(var zip=new ZipFile(plain.toFile())) {
                var names=new HashSet<String>();var entries=zip.entries();
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();String name=entry.getName();
                    if(entry.isDirectory()||!names.add(name)||names.size()>InstanceConfiguration.MAX_FILES+3||!(Set.of("manifest.json","database.json","configuration.json").contains(name)||InstanceConfiguration.validFileName(name)))throw invalid();
                }
                if(!names.containsAll(Set.of("manifest.json","database.json","configuration.json")))throw invalid();
                byte[] metadata=read(zip,"manifest.json",MAX_MANIFEST);manifest=readObject(metadata);validateManifest(manifest,names,maximumBytes-metadata.length);
                byte[] settings=null;
                try {
                    for(var fields=manifest.path("files").fields();fields.hasNext();) {
                        var file=fields.next();String name=file.getKey();var expected=file.getValue();long length=expected.path("bytes").asLong();
                        var entry=zip.getEntry(name);if(entry.getSize()!=length)throw invalid();
                        if(name.equals("database.json"))try(var output=Files.newOutputStream(database)){copy(zip,name,output,expected,length);}
                        else {
                            long bound=name.equals("configuration.json")?MAX_CONFIGURATION:InstanceConfiguration.MAX_FILE_BYTES;
                            if(length>bound)throw invalid();var output=new ByteArrayOutputStream();copy(zip,name,output,expected,bound);
                            if(name.equals("configuration.json"))settings=output.toByteArray();else contents.put(name,output.toByteArray());
                        }
                    }
                    if(settings==null)throw invalid();configuration=readObject(settings);
                }finally{if(settings!=null)Arrays.fill(settings,(byte)0);}
            }
            InstanceConfiguration.validate(configuration,contents.keySet());
            if(!manifest.path("source").path("databaseType").asText().equals(configuration.path("properties").path("quarkus.datasource.db-kind").asText()))throw invalid();
            var counts=InstanceDatabaseImage.preflight(database,stage,InstanceAuthenticationPreflight::inspect);Files.delete(plain);
            var verified=new Verified(stage,database,manifest,new InstanceConfiguration.Snapshot(configuration,contents),counts);complete=true;return verified;
        }catch(InstanceArchiveRejection failure){throw failure;}
        catch(Exception failure){throw invalid();}
        finally {if(!complete){contents.values().forEach(bytes->Arrays.fill(bytes,(byte)0));remove(stage);}}
    }

    private static void validateManifest(ObjectNode manifest,Set<String> zipNames,long limit) throws IOException {
        if(!fields(manifest).equals(Set.of("format","archiveId","schema","createdAt","source","files"))||!FORMAT.equals(manifest.path("format").asText())
            ||!manifest.path("schema").isIntegralNumber()||!manifest.path("schema").canConvertToInt()||manifest.path("schema").asInt()!=ServerSchema.CURRENT||!manifest.path("files").isObject())throw invalid();
        String archiveId=text(manifest,"archiveId",36);if(!UUID.fromString(archiveId).toString().equals(archiveId))throw invalid();Instant.parse(text(manifest,"createdAt",100));validateSource(manifest.path("source"));
        var expected=new HashSet<>(fields(manifest.path("files")));expected.add("manifest.json");if(!expected.equals(zipNames))throw invalid();
        long total=0,configTotal=0;
        for(var iterator=manifest.path("files").fields();iterator.hasNext();) {
            var file=iterator.next();String name=file.getKey();var index=file.getValue();
            if(name.equals("manifest.json")||!index.isObject()||!fields(index).equals(Set.of("bytes","sha256"))||!index.path("bytes").isIntegralNumber()||!index.path("bytes").canConvertToLong())throw invalid();
            long size=index.path("bytes").asLong();if(size<0||size>limit-total)throw invalid();total+=size;
            if(!index.path("sha256").isTextual()||!index.path("sha256").asText().matches("[0-9a-f]{64}"))throw invalid();
            if(name.startsWith("configuration/")){if(size>InstanceConfiguration.MAX_FILE_BYTES||size>InstanceConfiguration.MAX_TOTAL_BYTES-configTotal)throw invalid();configTotal+=size;}
        }
    }
    private static void validateSource(JsonNode source) throws IOException {
        if(!source.isObject()||!fields(source).equals(SOURCE_FIELDS))throw invalid();
        text(source,"serverVersion",100);String commit=text(source,"commit",40);if(!commit.equals("unknown")&&!commit.matches("[0-9a-f]{40}"))throw invalid();
        if(!Set.of("h2","postgresql").contains(text(source,"databaseType",16))||!text(source,"packageId",64).matches("[a-z][a-z0-9_-]{0,63}"))throw invalid();
        if(!source.path("publicOrigin").isNull())RecoveryOrigin.validate(text(source,"publicOrigin",2048));
    }
    private static String text(JsonNode value,String name,int limit) throws IOException {var field=value.path(name);if(!field.isTextual()||field.asText().isEmpty()||field.asText().length()>limit)throw invalid();return field.asText();}
    private static Set<String> fields(JsonNode value){var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);return fields;}
    private static ObjectNode readObject(byte[] bytes) throws IOException {JsonNode result=READER.readTree(bytes);if(result==null||!result.isObject())throw invalid();return (ObjectNode)result;}
    private static byte[] read(ZipFile zip,String name,int limit) throws IOException {var entry=zip.getEntry(name);if(entry==null||entry.getSize()<0||entry.getSize()>limit)throw invalid();try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit||bytes.length!=entry.getSize())throw invalid();return bytes;}}
    @FunctionalInterface private interface Writer {void write(OutputStream output) throws Exception;}
    private static void entry(ZipOutputStream zip,String name,ObjectNode index,Writer writer) throws Exception {
        zip.putNextEntry(new ZipEntry(name));var output=new DigestOutput(zip);writer.write(output);zip.closeEntry();
        index.set(name,object().put("bytes",output.bytes).put("sha256",HexFormat.of().formatHex(output.digest.digest())));
    }
    private static void copy(ZipFile zip,String name,OutputStream target,JsonNode expected,long limit) throws IOException {
        var output=new DigestOutput(target);byte[] buffer=new byte[64*1024];
        try(var input=zip.getInputStream(zip.getEntry(name))) {
            for(int count;(count=input.read(buffer))!=-1;){if(count>limit-output.bytes)throw invalid();output.write(buffer,0,count);}
            if(output.bytes!=expected.path("bytes").asLong()||!HexFormat.of().formatHex(output.digest.digest()).equals(expected.path("sha256").asText()))throw invalid();
        }finally{Arrays.fill(buffer,(byte)0);}
    }
    private static final class DigestOutput extends OutputStream {
        private final OutputStream target;private final MessageDigest digest;private long bytes;
        DigestOutput(OutputStream target) throws IOException {this.target=target;try{digest=MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException unavailable){throw new IOException("INSTANCE_BACKUP_UNAVAILABLE");}}
        @Override public void write(int value) throws IOException {target.write(value);digest.update((byte)value);bytes++;}
        @Override public void write(byte[] value,int offset,int length) throws IOException {target.write(value,offset,length);digest.update(value,offset,length);bytes+=length;}
    }
    private static Path privateDirectory(Path root) throws IOException {return Files.createTempDirectory(root,".instance-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
    private static Path privateFile(Path folder,String suffix) throws IOException {return Files.createTempFile(folder,".instance-",suffix,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
    static void remove(Path folder) throws IOException {try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}catch(IOException failure){throw new IOException("INSTANCE_BACKUP_CLEANUP_FAILED");}}
    private static IOException invalid(){return new IOException("INVALID_INSTANCE_BACKUP");}
}
