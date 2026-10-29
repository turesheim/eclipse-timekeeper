package net.resheim.timekeeper.server;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static net.resheim.timekeeper.server.InstanceArtifacts.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;

/** Immutable recovered security material; source paths are never write destinations. */
final class InstanceRestoreMaterial {
    private InstanceRestoreMaterial() { }
    private static final String FORMAT="timekeeper-instance-material-v1";
    private static final long MAX_BYTES=70_000_000;
    private static final ObjectReader JSON=MAPPER.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Installed(UUID generation,UUID operation,String intentDigest,Path directory,String digest) {
        Path configuration(){return directory.resolve("application.properties");}
        @Override public String toString(){return "Private recovered instance material";}
    }
    static Installed install(InstanceRestoreIntent.Prepared prepared) throws IOException {return install(prepared.storage(),prepared.spec(),prepared.digest(),prepared.archive());}
    static Installed install(InstanceRestoreIntent.Staged staged,InstanceBackupArchive.Verified archive) throws IOException {return install(staged.storage(),staged.spec(),staged.digest(),archive);}
    private static Installed install(Path root,InstanceRestoreIntent.Spec spec,String intentDigest,InstanceBackupArchive.Verified archive) throws IOException {
        directory(root);Path material=root.resolve("material");mkdir(material);sync(root);
        Path destination=material.resolve(spec.generation().toString());
        if(Files.exists(destination,NOFOLLOW_LINKS))return expected(read(root,spec.generation(),null),spec,intentDigest);
        Path stage=material.resolve(".install-"+UUID.randomUUID());mkdir(stage);boolean renamed=false;
        try {
            InstanceRestoreDatabase.verifyArchive(archive);var source=archive.configuration();
            var metadata=object().put("format",FORMAT).put("generation",spec.generation().toString()).put("operation",spec.operation().toString()).put("intentDigest",intentDigest);var entries=metadata.putObject("entries");
            byte[] configuration=MAPPER.writeValueAsBytes(source.document());try{write(stage,"source-configuration.json",configuration,entries);}finally{Arrays.fill(configuration,(byte)0);}
            var inventory=new ArrayList<String>();inventory.add(destination.resolve("source-configuration.json").toString());
            for(var entry:new TreeMap<>(source.files()).entrySet()) {
                String name="source/"+entry.getKey();write(stage,name,entry.getValue(),entries);inventory.add(destination.resolve(name).toString());
            }
            String tokens="NONE";var reference=source.document().path("files").path("timekeeper.auth.tokens-file");
            if(reference.isArray()) {
                String name="source/"+reference.get(0).asText();tokens=destination.resolve(name).toString();
                var registry=new TokenRegistry();registry.tokensFile=stage.resolve(name).toString();try{registry.load();}catch(IllegalStateException invalid){throw new IOException("INVALID_RECOVERED_TOKEN_REGISTRY");}
            }
            String trusted=source.document().path("properties").path("timekeeper.calendars.trusted-origins").asText("");
            try{new CalendarDownload(trusted);}catch(IllegalArgumentException invalid){throw new IOException("INVALID_RECOVERED_CALENDAR_CONFIGURATION");}
            String automatic=source.document().path("properties").path("timekeeper.calendars.automatic").asText("true");
            final boolean refresh;try{refresh=new io.smallrye.config.SmallRyeConfigBuilder().withSources(new io.smallrye.config.PropertiesConfigSource(Map.of("automatic",automatic),"recovered policy",100)).build().getValue("automatic",Boolean.class);}catch(RuntimeException invalid){throw new IOException("INVALID_RECOVERED_CALENDAR_CONFIGURATION");}
            var values=new Properties();values.setProperty("timekeeper.calendars.trusted-origins",literal(trusted));values.setProperty("timekeeper.calendars.automatic",Boolean.toString(refresh));values.setProperty("config_ordinal","450");values.setProperty("timekeeper.package-id",spec.deployment().packageId());
            values.setProperty("timekeeper.auth.tokens-file",literal(tokens));values.setProperty("timekeeper.auth.setup-file","NONE");
            values.setProperty("timekeeper.service-settings.recover","false");
            values.setProperty("timekeeper.instance-restore.storage",literal(root.toString()));values.setProperty("timekeeper.instance-restore.generation",spec.generation().toString());
            values.setProperty("timekeeper.instance-restored.files",literal(String.join(",",inventory.stream().map(InstanceRestoreMaterial::csv).toList())));
            var output=new ByteArrayOutputStream();values.store(output,"Recovered instance bindings; database and listener settings stay with the destination");byte[] bytes=output.toByteArray();try{write(stage,"application.properties",bytes,entries);}finally{Arrays.fill(bytes,(byte)0);}
            byte[] receipt=MAPPER.writeValueAsBytes(metadata);write(stage,"material.json",receipt,null);sync(stage);Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);renamed=true;sync(material);
            return expected(read(root,spec.generation(),null),spec,intentDigest);
        }finally{if(!renamed&&Files.exists(stage,NOFOLLOW_LINKS))InstanceBackupArchive.remove(stage);}
    }
    /** A published pointer never makes an uncommitted database phase active. */
    static void publish(InstanceRestoreIntent.Prepared prepared,Connection connection,InstanceRestoreFence.Exclusive exclusive,Installed material) throws IOException,SQLException {
        exclusive.require(connection);if(!connection.getAutoCommit())throw new SQLException("Publish recovered material after the database commit");
        if(!prepared.phase(connection).equals("DATABASE_APPLIED"))throw new IOException("INSTANCE_RESTORE_NOT_APPLIED");
        expected(read(prepared.storage(),material.generation(),material.digest()),prepared);
        var pointer=object().put("format",FORMAT).put("generation",material.generation().toString()).put("sha256",material.digest());
        atomic(prepared.storage(),"active.json",MAPPER.writeValueAsBytes(pointer));
    }
    static Installed active(Path root) throws IOException {
        root=root.toAbsolutePath().normalize();directory(root);var pointer=json(root.resolve("active.json"),1024);fields(pointer,Set.of("format","generation","sha256"));
        if(!pointer.path("format").asText().equals(FORMAT)||!pointer.path("sha256").isTextual()||!pointer.path("sha256").asText().matches("[a-f0-9]{64}"))throw invalid();
        return read(root,uuid(pointer.path("generation")),pointer.path("sha256").asText());
    }
    static void discardUnpublished(Path root,UUID generation,String intentDigest) throws IOException {
        if(Files.exists(root.resolve("active.json"),NOFOLLOW_LINKS)&&active(root).generation().equals(generation))throw invalid();
        Path parent=root.resolve("material"),path=parent.resolve(generation.toString());if(!Files.exists(parent,NOFOLLOW_LINKS))return;directory(parent);if(!Files.exists(path,NOFOLLOW_LINKS))return;
        var value=read(root,generation,null);if(!value.intentDigest().equals(intentDigest))throw invalid();InstanceBackupArchive.remove(path);sync(parent);
    }
    private static Installed expected(Installed material,InstanceRestoreIntent.Prepared prepared) throws IOException {return expected(material,prepared.spec(),prepared.digest());}
    private static Installed expected(Installed material,InstanceRestoreIntent.Spec spec,String digest) throws IOException {
        if(!material.generation().equals(spec.generation())||!material.operation().equals(spec.operation())||!material.intentDigest().equals(digest))throw invalid();return material;
    }
    private static Installed read(Path root,UUID generation,String expected) throws IOException {
        try {
            directory(root);directory(root.resolve("material"));Path path=root.resolve("material").resolve(generation.toString());directory(path);file(path.resolve("material.json"));String digest=sha256(path.resolve("material.json"));if(expected!=null&&!digest.equals(expected))throw invalid();
            var metadata=json(path.resolve("material.json"),131072);fields(metadata,Set.of("format","generation","operation","intentDigest","entries"));
            if(!metadata.path("format").asText().equals(FORMAT)||!uuid(metadata.path("generation")).equals(generation)||!metadata.path("intentDigest").isTextual()||!metadata.path("intentDigest").asText().matches("[a-f0-9]{64}")||!metadata.path("entries").isObject())throw invalid();
            var entries=metadata.path("entries");if(entries.size()>InstanceConfiguration.MAX_FILES+2||!entries.has("application.properties")||!entries.has("source-configuration.json"))throw invalid();long total=0;
            for(var iterator=entries.fields();iterator.hasNext();) {
                var entry=iterator.next();String name=entry.getKey();if(!Set.of("application.properties","source-configuration.json").contains(name)&&!name.matches("source/configuration/[0-9]{4}\\.(?:bin|p12|pfx|jks|pem|crt|key|json|properties|der)"))throw invalid();
                Path input=path.resolve(name);parents(path,input.getParent(),false);file(input);var receipt=entry.getValue();fields(receipt,Set.of("bytes","sha256"));
                long limit=name.equals("application.properties")?1_000_000:name.equals("source-configuration.json")?4_000_000:InstanceConfiguration.MAX_FILE_BYTES;
                if(!receipt.path("bytes").isIntegralNumber()||!receipt.path("bytes").canConvertToLong()||receipt.path("bytes").asLong()<0||receipt.path("bytes").asLong()>limit||Files.size(input)!=receipt.path("bytes").asLong()||!receipt.path("sha256").isTextual()||!sha256(input).equals(receipt.path("sha256").asText()))throw invalid();
                total=Math.addExact(total,Files.size(input));if(total>MAX_BYTES)throw invalid();
            }
            return new Installed(generation,uuid(metadata.path("operation")),metadata.path("intentDigest").asText(),path,digest);
        }catch(IOException failure){throw failure;}catch(RuntimeException failure){throw invalid();}
    }
    private static String literal(String text){return text.replace("${","\\${");}
    private static String csv(String text){return text.replace("\\","\\\\").replace(",","\\,");}
    private static void mkdir(Path path) throws IOException {if(!Files.exists(path,NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));directory(path);}
    private static void parents(Path root,Path path,boolean create) throws IOException {if(path.equals(root))return;parents(root,path.getParent(),create);if(create)mkdir(path);else directory(path);sync(path.getParent());}
    private static void write(Path root,String name,byte[] bytes,ObjectNode entries) throws IOException {
        long total=bytes.length;if(entries!=null){for(var value:entries)total=Math.addExact(total,value.path("bytes").asLong());if(total>MAX_BYTES)throw invalid();}
        Path path=root.resolve(name);parents(root,path.getParent(),true);try(var file=createFile(path)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}sync(path.getParent());if(entries!=null)entries.putObject(name).put("bytes",bytes.length).put("sha256",sha256(path));
    }
    private static void atomic(Path root,String name,byte[] bytes) throws IOException {String temporary=".active-"+UUID.randomUUID();try{write(root,temporary,bytes,null);Files.move(root.resolve(temporary),root.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(root);}finally{Files.deleteIfExists(root.resolve(temporary));}}
    private static ObjectNode json(Path path,int limit) throws IOException {file(path);if(Files.size(path)>limit)throw invalid();try(var input=Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS)){var value=JSON.readTree(input);if(value==null||!value.isObject())throw invalid();return (ObjectNode)value;}}
    private static UUID uuid(JsonNode node) throws IOException {try{if(!node.isTextual())throw invalid();UUID id=UUID.fromString(node.asText());if(!id.toString().equals(node.asText()))throw invalid();return id;}catch(RuntimeException failure){throw invalid();}}
    private static void fields(JsonNode node,Set<String> expected) throws IOException {var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);if(!node.isObject()||!fields.equals(expected))throw invalid();}
    private static IOException invalid(){return new IOException("INVALID_INSTANCE_RESTORE_MATERIAL");}
}
