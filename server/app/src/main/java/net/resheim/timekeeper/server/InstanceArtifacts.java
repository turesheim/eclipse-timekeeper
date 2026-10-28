package net.resheim.timekeeper.server;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Private, bounded, crash-resumable ciphertext staging. No authorization is implied. */
final class InstanceArtifacts {
    static final int CHUNK_BYTES=1024*1024;
    private static final Set<PosixFilePermission> DIRECTORY=PosixFilePermissions.fromString("rwx------"),FILE=PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final Path root;private final UUID identity;private final long maximumBytes,retainedBytes;private final int maximumJobs;
    record Upload(long bytes,long received,boolean complete) { }
    @FunctionalInterface interface Reader<T> {T read(InputStream input,long bytes) throws IOException;}
    @FunctionalInterface interface PrivateReader<T> {T read(Path file,long bytes) throws IOException;}
    InstanceArtifacts(Path root,long maximumBytes,long retainedBytes,int maximumJobs) throws IOException {
        if(maximumBytes<1||retainedBytes<maximumBytes||maximumJobs<1)throw invalid();
        this.root=root.toAbsolutePath().normalize();this.maximumBytes=maximumBytes;this.retainedBytes=retainedBytes;this.maximumJobs=maximumJobs;
        try {
            if(!Files.exists(this.root,NOFOLLOW_LINKS))try{Files.createDirectory(this.root,PosixFilePermissions.asFileAttribute(DIRECTORY));}catch(FileAlreadyExistsException race){/* Another replica initialized it. */}
            directory(this.root);identity=locked(this.root,this::loadIdentity);
        }
        catch(IOException|RuntimeException failure){throw unavailable();}
    }
    UUID identity(){return identity;}
    void create(UUID id,long bytes,String digest) throws IOException {
        if(id==null||bytes<1||bytes>maximumBytes||digest==null||!digest.matches("[a-f0-9]{64}"))throw invalid();
        locked(root,()->{
            long retained=0;int count=0;
            try(var paths=Files.list(root)) {
                for(Path path:paths.toList()) {
                    if(Set.of(".lock",".identity").contains(path.getFileName().toString()))continue;
                    identifier(path);directory(path);var metadata=metadata(path);count++;
                    try {retained=Math.addExact(retained,metadata.path("bytes").asLong());}catch(ArithmeticException failure){throw unavailable();}
                }
            }
            if(count>=maximumJobs||bytes>retainedBytes-retained)throw new IOException("INSTANCE_ARTIFACT_QUOTA");
            Path job=job(id);if(Files.exists(job,NOFOLLOW_LINKS))throw invalid();
            Files.createDirectory(job,PosixFilePermissions.asFileAttribute(DIRECTORY));
            try {
                try(var file=createFile(job.resolve("input.part"))){file.force(true);}
                save(job,object().put("format",1).put("bytes",bytes).put("received",0).put("sha256",digest).put("complete",false));sync(root);
            }catch(IOException failure){throw unavailable();}
            return null;
        });
    }
    /** Reserve capacity before any plaintext snapshot is written. */
    Path reserveBackup(UUID id) throws IOException {
        create(id,maximumBytes,"0".repeat(64));
        return locked(job(id),()->{Path work=job(id).resolve("work");Files.createDirectory(work,PosixFilePermissions.asFileAttribute(DIRECTORY));sync(job(id));return work;});
    }
    /** Publish only a server-produced encrypted file from this job's working area. */
    Upload publishBackup(UUID id,Path encrypted) throws IOException {
        locked(job(id),()->{
            Path job=job(id),work=job.resolve("work");directory(work);
            if(!encrypted.toAbsolutePath().normalize().getParent().equals(work))throw invalid();file(encrypted);
            try(var input=Files.newInputStream(encrypted,StandardOpenOption.READ,NOFOLLOW_LINKS)) {
                if(!Arrays.equals(input.readNBytes(8),"TKIBK001".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw invalid();
            }
            long length=Files.size(encrypted);if(length<40||length>maximumBytes)throw new IOException("INSTANCE_ARTIFACT_QUOTA");
            var metadata=metadata(job);if(metadata.path("complete").asBoolean()||metadata.path("received").asLong()!=0)throw invalid();
            String digest=sha256(encrypted);
            try(var output=FileChannel.open(encrypted,StandardOpenOption.WRITE,NOFOLLOW_LINKS)){output.force(true);}
            Files.move(encrypted,job.resolve("input.part"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(job);
            metadata.put("bytes",length).put("received",length).put("sha256",digest);save(job,metadata);
            cleanWork(work);sync(job);return null;
        });
        return complete(id);
    }
    Upload status(UUID id) throws IOException {return locked(job(id),()->view(metadata(job(id))));}
    Upload append(UUID id,long offset,byte[] bytes) throws IOException {
        if(offset<0||bytes==null||bytes.length<1||bytes.length>CHUNK_BYTES)throw invalid();
        return locked(job(id),()->{
            Path job=job(id);var metadata=metadata(job);long received=metadata.path("received").asLong(),length=metadata.path("bytes").asLong();
            if(offset>received||bytes.length>length-offset)throw invalid();
            Path input=job.resolve(metadata.path("complete").asBoolean()?"archive.tki":"input.part");file(input);
            try(var output=FileChannel.open(input,StandardOpenOption.READ,StandardOpenOption.WRITE,NOFOLLOW_LINKS)) {
                if(output.size()<received)throw unavailable();
                if(offset<received) {
                    if(bytes.length>received-offset)throw new IOException("INSTANCE_ARTIFACT_CONFLICT");
                    byte[] previous=new byte[bytes.length];readFully(output,ByteBuffer.wrap(previous),offset);
                    if(!MessageDigest.isEqual(bytes,previous))throw new IOException("INSTANCE_ARTIFACT_CONFLICT");return view(metadata);
                }
                if(metadata.path("complete").asBoolean())throw invalid();
                // A crash can leave fsynced bytes beyond the last durable receipt.
                // Those bytes were never acknowledged and are safe to retry.
                output.truncate(received);output.position(received);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
            }
            metadata.put("received",received+bytes.length);save(job,metadata);return view(metadata);
        });
    }
    Upload complete(UUID id) throws IOException {
        return locked(job(id),()->{
            Path job=job(id);var metadata=metadata(job);long expected=metadata.path("bytes").asLong();
            if(metadata.path("received").asLong()!=expected)throw invalid();
            Path part=job.resolve("input.part"),archive=job.resolve("archive.tki");
            boolean renamed=Files.exists(archive,NOFOLLOW_LINKS);Path source=renamed?archive:part;file(source);
            if(Files.size(source)!=expected||!sha256(source).equals(metadata.path("sha256").asText()))throw invalid();
            // Recovery after rename but before the metadata commit is idempotent.
            if(!renamed){Files.move(part,archive,StandardCopyOption.ATOMIC_MOVE);sync(job);}
            metadata.put("complete",true);save(job,metadata);return view(metadata);
        });
    }
    Path validationWork(UUID id) throws IOException {
        return locked(job(id),()->{
            if(!metadata(job(id)).path("complete").asBoolean())throw invalid();Path work=job(id).resolve("work");
            Files.createDirectory(work,PosixFilePermissions.asFileAttribute(DIRECTORY));sync(job(id));return work;
        });
    }
    void clearWork(UUID id) throws IOException {
        locked(job(id),()->{Path work=job(id).resolve("work");if(Files.exists(work,NOFOLLOW_LINKS)){cleanWork(work);sync(job(id));}return null;});
    }
    <T> T inspect(UUID id,PrivateReader<T> reader) throws IOException {
        return locked(job(id),()->{
            Path job=job(id);var metadata=metadata(job);if(!metadata.path("complete").asBoolean())throw invalid();
            Path archive=job.resolve("archive.tki");file(archive);if(Files.size(archive)!=metadata.path("bytes").asLong()||!sha256(archive).equals(metadata.path("sha256").asText()))throw unavailable();
            return reader.read(archive,metadata.path("bytes").asLong());
        });
    }
    <T> T read(UUID id,Reader<T> reader) throws IOException {
        // Hold the per-job lock for the whole stream, including through another
        // store instance. Discard cannot unlink an active download.
        return inspect(id,(archive,bytes)->{try(var input=Files.newInputStream(archive,StandardOpenOption.READ,NOFOLLOW_LINKS)){return reader.read(input,bytes);}});
    }
    void remove(UUID id) throws IOException {
        locked(root,()->{
            Path job=job(id);if(!Files.exists(job,NOFOLLOW_LINKS))return null;
            locked(job,()->{
                try(var paths=Files.list(job)) {
                    for(Path path:paths.toList()) {
                        if(path.getFileName().toString().equals("work")){validateWork(path);continue;}
                        if(!Set.of(".lock","upload.json","input.part","archive.tki").contains(path.getFileName().toString())&&!path.getFileName().toString().matches("\\.receipt-[a-zA-Z0-9-]+"))throw unavailable();
                        file(path);
                    }
                }
                try(var paths=Files.list(job)){for(Path path:paths.toList())if(!path.getFileName().toString().equals(".lock")) {
                    if(path.getFileName().toString().equals("work"))cleanWork(path);else Files.delete(path);
                }}
                return null;
            });
            Files.delete(job.resolve(".lock"));Files.delete(job);sync(root);return null;
        });
    }
    private static void validateWork(Path work) throws IOException {
        directory(work);try(var paths=Files.walk(work)) {
            for(Path path:paths.toList())if(Files.isDirectory(path,NOFOLLOW_LINKS))directory(path);else file(path);
        }
    }
    private static void cleanWork(Path work) throws IOException {
        validateWork(work);try(var paths=Files.walk(work)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    private UUID loadIdentity() throws IOException {
        Path marker=root.resolve(".identity");
        if(!Files.exists(marker,NOFOLLOW_LINKS)) {
            try(var paths=Files.list(root)){if(paths.anyMatch(path->!path.getFileName().toString().equals(".lock")))throw unavailable();}
            // The configured parent must already exist. Persist its new root
            // entry before publishing the identity used by database receipts.
            sync(root.getParent());
            try(var output=createFile(marker)) {
                var bytes=ByteBuffer.wrap((UUID.randomUUID()+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));while(bytes.hasRemaining())output.write(bytes);output.force(true);
            }
            sync(root);
        }
        file(marker);if(Files.size(marker)!=37)throw unavailable();
        String value=Files.readString(marker,java.nio.charset.StandardCharsets.US_ASCII);
        try {UUID id=UUID.fromString(value.strip());if(!value.equals(id+"\n"))throw unavailable();return id;}
        catch(IllegalArgumentException failure){throw unavailable();}
    }
    private Path job(UUID id) throws IOException {if(id==null)throw invalid();return root.resolve(id.toString());}
    private static UUID identifier(Path path) throws IOException {try{var id=UUID.fromString(path.getFileName().toString());if(!id.toString().equals(path.getFileName().toString()))throw invalid();return id;}catch(IllegalArgumentException failure){throw unavailable();}}
    private ObjectNode metadata(Path job) throws IOException {
        Path path=job.resolve("upload.json");file(path);if(Files.size(path)>1024)throw unavailable();
        try(var input=Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS);var parser=JSON.createParser(input)) {
            JsonNode value=JSON.readTree(parser);if(parser.nextToken()!=null||value==null||!value.isObject())throw unavailable();
            var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
            if(!fields.equals(Set.of("format","bytes","received","sha256","complete"))||!value.path("format").isInt()||value.path("format").intValue()!=1
                ||!value.path("bytes").isIntegralNumber()||!value.path("bytes").canConvertToLong()||value.path("bytes").asLong()<1||value.path("bytes").asLong()>maximumBytes
                ||!value.path("received").isIntegralNumber()||!value.path("received").canConvertToLong()||value.path("received").asLong()<0||value.path("received").asLong()>value.path("bytes").asLong()
                ||!value.path("sha256").isTextual()||!value.path("sha256").asText().matches("[a-f0-9]{64}")||!value.path("complete").isBoolean()
                ||value.path("complete").asBoolean()&&value.path("received").asLong()!=value.path("bytes").asLong())throw unavailable();
            return (ObjectNode)value;
        }catch(IOException|RuntimeException failure){throw unavailable();}
    }
    private static Upload view(ObjectNode value){return new Upload(value.path("bytes").asLong(),value.path("received").asLong(),value.path("complete").asBoolean());}
    private static void save(Path job,ObjectNode metadata) throws IOException {
        Path temporary=Files.createTempFile(job,".receipt-","",PosixFilePermissions.asFileAttribute(FILE));
        try {
            try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,NOFOLLOW_LINKS)){var bytes=ByteBuffer.wrap(metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())output.write(bytes);output.force(true);}
            Files.move(temporary,job.resolve("upload.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(job);
        }finally {Files.deleteIfExists(temporary);}
    }
    @FunctionalInterface private interface Work<T> {T run() throws IOException;}
    private <T> T locked(Path directory,Work<T> work) throws IOException {
        try {
            if(identity!=null&&!loadIdentity().equals(identity))throw unavailable();
            directory(directory);Path lock=directory.resolve(".lock");
            if(!Files.exists(lock,NOFOLLOW_LINKS))try(var created=createFile(lock)){created.force(true);}catch(FileAlreadyExistsException race){/* Another process created the lock. */}
            file(lock);
            try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE,NOFOLLOW_LINKS);var held=channel.tryLock()) {
                if(held==null)throw new IOException("INSTANCE_ARTIFACT_BUSY");return work.run();
            }
        }catch(OverlappingFileLockException failure){throw new IOException("INSTANCE_ARTIFACT_BUSY");}
        catch(IOException failure){if(Set.of("INVALID_INSTANCE_ARTIFACT","INSTANCE_ARTIFACT_QUOTA","INSTANCE_ARTIFACT_BUSY","INSTANCE_ARTIFACT_CONFLICT").contains(failure.getMessage()))throw failure;throw unavailable();}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static FileChannel createFile(Path file) throws IOException {return FileChannel.open(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(FILE));}
    private static void directory(Path path) throws IOException {if(!Files.isDirectory(path,NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(path,NOFOLLOW_LINKS).equals(DIRECTORY))throw unavailable();}
    private static void file(Path path) throws IOException {if(!Files.isRegularFile(path,NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(path,NOFOLLOW_LINKS).equals(FILE)||((Number)Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS)).longValue()!=1)throw unavailable();}
    private static void sync(Path directory) throws IOException {try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}}
    private static void readFully(FileChannel channel,ByteBuffer buffer,long offset) throws IOException {while(buffer.hasRemaining()){int count=channel.read(buffer,offset);if(count<0)throw unavailable();offset+=count;}}
    private static String sha256(Path path) throws IOException {
        try {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS)){byte[] bytes=new byte[64*1024];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException failure){throw unavailable();}
    }
    private static IOException unavailable(){return new IOException("INSTANCE_ARTIFACT_UNAVAILABLE");}
    private static IOException invalid(){return new IOException("INVALID_INSTANCE_ARTIFACT");}
}
