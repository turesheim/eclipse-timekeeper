package net.resheim.timekeeper.server;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Replica-local derived caches. Cache failures never participate in canonical writes. */
@ApplicationScoped
class NotesSearchService implements AutoCloseable {
    interface Backend {
        String key();
        NotesSearchSources sources(ServerStore store);
        NotesIndexReplay open(Path path,NotesSearchSources sources,NotesSearchSources.State state)throws IOException;
    }
    static final Backend NOTES=new Backend(){
        public String key(){return "notes";}
        public NotesSearchSources sources(ServerStore store){return new NotesSearchSources(store);}
        public NotesIndexReplay open(Path path,NotesSearchSources sources,NotesSearchSources.State state)throws IOException{return NotesSearchReplay.open(path,sources,state);}
    };
    @Inject ServerStore store;
    @ConfigProperty(name="timekeeper.notes.search.cache-directory",defaultValue="AUTO") String configuredDirectory="AUTO";
    @ConfigProperty(name="timekeeper.notes.search.open-owners",defaultValue="8") int maximumOwners=8;
    @ConfigProperty(name="timekeeper.notes.search.max-cache-bytes",defaultValue="16000000000") long maximumBytes=16_000_000_000L;
    @ConfigProperty(name="timekeeper.notes.search.min-free-bytes",defaultValue="1073741824") long minimumFreeBytes=1_073_741_824L;
    private static final NotesSearchReplay.Budget BATCH=new NotesSearchReplay.Budget(32,8_000_000,100);
    private static final String MARKER="Dagsverk private Notes search cache 1\n";
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(NotesSearchService.class);
    private final LinkedHashMap<String,Handle> handles=new LinkedHashMap<>(16,.75f,true);
    private final Set<String> backends=new HashSet<>(Set.of(NOTES.key()));
    private final ArrayDeque<Handle> closing=new ArrayDeque<>();
    private final Set<String> closingOwners=new HashSet<>();
    private final Semaphore queries=new Semaphore(2);
    private Path root;
    private boolean temporary,closed;
    private FileChannel channel;
    private FileLock fileLock;
    private ScheduledExecutorService worker;
    private long sweepAt,measureAt,measuredBytes;
    private int rotation;
    private static final class Handle {
        final String owner,key;final Path folder;final Backend backend;
        volatile NotesIndexReplay current,building;
        volatile Path currentPath,buildingPath;
        volatile boolean rebuild,removed,broken;
        volatile String error;
        volatile long touched=System.nanoTime(),retryAfter,nextPoll;
        Handle(String owner,String key,Path folder,Backend backend){this.owner=owner;this.key=key;this.folder=folder;this.backend=backend;}
    }
    record Status(String state,long generation,long target,boolean rebuilding,String error){}
    NotesSearchService(){}
    NotesSearchService(ServerStore store,Path directory){this.store=store;configuredDirectory=directory.toString();}
    synchronized void register(Backend backend){if(!backend.key().matches("[a-z][a-z0-9-]{0,31}"))throw new IllegalArgumentException("Invalid search backend key");backends.add(backend.key());}
    private static String ownerKey(String owner){return ArchiveJournalFiles.digest(owner.getBytes(StandardCharsets.UTF_8));}
    private static String ownerKey(String owner,String backend){return backend.equals(NOTES.key())?ownerKey(owner):ownerKey(owner+"\0"+backend);}
    private synchronized void initialize()throws IOException{
        if(closed)throw new IOException("Notes search is stopped");if(worker!=null)return;
        if(maximumOwners<1||maximumOwners>32||maximumBytes<1||minimumFreeBytes<0)throw new IOException("Invalid search cache owner limit");
        try{
        temporary=configuredDirectory.equals("AUTO");root=temporary?Files.createTempDirectory("timekeeper-notes-search-"):Path.of(configuredDirectory).toAbsolutePath().normalize();
        Path marker=root.resolve(".format");
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Notes search cache root");
            if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))try(var files=Files.list(root)){if(files.findAny().isPresent())throw new IOException("Notes search requires a dedicated cache directory");}
        }
        NotesLuceneIndex.privateDirectory(root);
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
            if(Files.isSymbolicLink(marker)||Files.size(marker)>256||!Files.readString(marker).equals(MARKER))throw new IOException("Invalid Notes search cache root");
        }else{
            try(var files=Files.list(root)){if(files.findAny().isPresent())throw new IOException("Notes search requires a dedicated cache directory");}
            Files.writeString(marker,MARKER,StandardOpenOption.CREATE_NEW);
        }
        NotesLuceneIndex.privateFile(marker);channel=FileChannel.open(root.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);NotesLuceneIndex.privateFile(root.resolve(".lock"));
        try{fileLock=channel.tryLock();if(fileLock==null)throw new IOException("Notes search cache is already in use");}
        catch(OverlappingFileLockException occupied){channel.close();channel=null;throw new IOException("Notes search cache is already in use");}
        worker=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"Dagsverk Notes search");thread.setDaemon(true);return thread;});
        worker.scheduleWithFixedDelay(this::tick,0,25,TimeUnit.MILLISECONDS);
        }catch(IOException|RuntimeException failure){
            try{if(fileLock!=null)fileLock.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}fileLock=null;
            try{if(channel!=null)channel.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}channel=null;
            try{if(temporary&&root!=null)remove(root);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof IOException io)throw io;throw new IOException("Notes search cache unavailable",failure);
        }
    }
    private synchronized Handle prepare(NotesSearchSources.State state,Backend backend)throws IOException{
        if(!backend.key().matches("[a-z][a-z0-9-]{0,31}"))throw new IllegalArgumentException("Invalid search backend key");
        backends.add(backend.key());initialize();String owner=state.identity().owner(),key=ownerKey(owner,backend.key());
        if(closingOwners.contains(key))throw new IOException("Notes search cache is busy");var existing=handles.get(key);if(existing!=null){existing.touched=System.nanoTime();var current=existing.current;if(current==null||!current.caughtUp(state))existing.nextPoll=0;return existing;}
        boolean owned=handles.values().stream().anyMatch(handle->handle.owner.equals(owner));
        if(!owned&&handles.values().stream().map(handle->handle.owner).distinct().count()>=maximumOwners){
            var activity=new HashMap<String,Long>();for(var handle:handles.values())activity.merge(handle.owner,handle.touched,Math::max);
            String retiredOwner=activity.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
            var retired=handles.values().stream().filter(handle->handle.owner.equals(retiredOwner)).toList();
            if(closing.size()+retired.size()>4)throw new IOException("Notes search cache is busy");
            for(var evicted:retired){handles.remove(evicted.key);evicted.removed=true;closingOwners.add(evicted.key);closing.add(evicted);}
        }
        var handle=new Handle(owner,key,root.resolve(key),backend);handles.put(key,handle);return handle;
    }
    Status status(NotesSearchSources.State state){
        return status(state,NOTES);
    }
    Status status(NotesSearchSources.State state,Backend backend){
        try{var handle=prepare(state,backend);var current=handle.current;var saved=current==null?null:current.index().watermark();
            return new Status(!handle.broken&&current!=null&&current.caughtUp(state)?"READY":handle.error==null?"INDEXING":"UNAVAILABLE",saved==null?0:saved.generation(),state.changes().generation(),handle.rebuild||handle.building!=null,handle.error);
        }catch(IOException|RuntimeException unavailable){return new Status("UNAVAILABLE",0,state.changes().generation(),false,"CACHE_UNAVAILABLE");}
    }
    void rebuild(NotesSearchSources.State state)throws IOException{rebuild(state,NOTES);}
    void rebuild(NotesSearchSources.State state,Backend backend)throws IOException{var handle=prepare(state,backend);handle.rebuild=true;handle.retryAfter=0;handle.nextPoll=0;}
    NotesLuceneIndex.Page search(NotesSearchSources.State state,NotesSearchQuery.Parsed query,int limit,NotesLuceneIndex.Position after,long waitMillis)throws IOException{
        return search(state,query,limit,after,waitMillis,NOTES);
    }
    NotesLuceneIndex.Page search(NotesSearchSources.State state,NotesSearchQuery.Parsed query,int limit,NotesLuceneIndex.Position after,long waitMillis,Backend backend)throws IOException{
        return search(state,query,limit,after,waitMillis,backend,Set.of());
    }
    NotesLuceneIndex.Page search(NotesSearchSources.State state,NotesSearchQuery.Parsed query,int limit,NotesLuceneIndex.Position after,long waitMillis,Backend backend,Set<String> excluded)throws IOException{
        if(waitMillis<0||waitMillis>1000)throw new IllegalArgumentException("Invalid search wait budget");var handle=prepare(state,backend);long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
        do{
            if(handle.removed)throw new NotesLuceneIndex.Unavailable();var current=handle.current;
            if(!handle.broken&&current!=null&&current.caughtUp(state)){
                boolean acquired;
                try{acquired=queries.tryAcquire(50,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new NotesLuceneIndex.Unavailable();}
                if(!acquired)throw new NotesLuceneIndex.Unavailable();
                try{return current.index().search(query,state.changes().generation(),limit,after,state.configuration(),state.hiddenJournal(),1000,excluded);}
                catch(NotesLuceneIndex.Unavailable pending){throw pending;}
                catch(IOException|RuntimeException corrupt){handle.broken=true;handle.rebuild=true;handle.retryAfter=0;throw new NotesLuceneIndex.Unavailable();}finally{queries.release();}
            }
            if(handle.error!=null||System.nanoTime()>=deadline)throw new NotesLuceneIndex.Unavailable();
            try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new NotesLuceneIndex.Unavailable();}
        }while(true);
    }
    @FunctionalInterface private interface IndexRead<T>{T read(NotesLuceneIndex index)throws IOException;}
    private <T>T read(NotesSearchSources.State state,Backend backend,IndexRead<T> query)throws IOException{
        var handle=prepare(state,backend);var current=handle.current;
        if(handle.removed||handle.broken||current==null||!current.caughtUp(state))throw new NotesLuceneIndex.Unavailable();
        boolean acquired;try{acquired=queries.tryAcquire(50,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new NotesLuceneIndex.Unavailable();}
        if(!acquired)throw new NotesLuceneIndex.Unavailable();
        try{return query.read(current.index());}
        catch(NotesLuceneIndex.Unavailable pending){throw pending;}
        catch(IOException|RuntimeException corrupt){handle.broken=true;handle.rebuild=true;handle.retryAfter=0;throw new NotesLuceneIndex.Unavailable();}
        finally{queries.release();}
    }
    Set<String> sourceFences(NotesSearchSources.State state,Backend backend,String type)throws IOException{return read(state,backend,index->index.sourceFences(type,state.changes().generation()));}
    NotesLuceneIndex.Hit lookup(NotesSearchSources.State state,Backend backend,UUID id)throws IOException{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
        do{
            try{return read(state,backend,index->index.lookup(id,state.changes().generation(),state.configuration(),state.hiddenJournal()));}
            catch(NotesLuceneIndex.Unavailable pending){if(System.nanoTime()>=deadline)throw pending;}
            try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new NotesLuceneIndex.Unavailable();}
        }while(true);
    }
    private void tick(){
        try{
            Handle selected,retired;List<Handle> snapshot;
            synchronized(this){if(closed)return;retired=closing.poll();snapshot=List.copyOf(handles.values());selected=snapshot.isEmpty()?null:snapshot.get(Math.floorMod(rotation++,snapshot.size()));}
            if(retired!=null)try{closeHandle(retired,false);}finally{synchronized(this){closingOwners.remove(retired.key);}}
            if(System.nanoTime()>=sweepAt){sweepAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);sweep();}
            if(selected!=null&&!selected.removed){
                long now=System.nanoTime();
                if(now-selected.touched>TimeUnit.MINUTES.toNanos(10))retire(selected,false);
                else if(now>=selected.retryAfter&&now>=selected.nextPoll)advance(selected);
            }
        }catch(IOException|RuntimeException failure){LOG.warnf("Notes search maintenance unavailable category=%s",failure.getClass().getSimpleName());}
    }
    private void advance(Handle handle){
        try{
            var sources=handle.backend.sources(store);var state=sources.state(handle.owner);NotesLuceneIndex.privateDirectory(handle.folder);
            if(handle.current==null&&handle.building==null)discover(handle,sources,state);
            boolean work=handle.rebuild||handle.building!=null||handle.current==null||!handle.current.caughtUp(state);
            if(work&&!capacity()){fail(handle,"CACHE_CAPACITY");return;}
            handle.nextPoll=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(work?0:500);
            if(handle.current!=null&&!handle.current.caughtUp(state))try{handle.current.advance(BATCH);}catch(IOException|RuntimeException expired){
                var previous=handle.current;var path=handle.currentPath;handle.current=null;handle.currentPath=null;handle.rebuild=true;
                try{previous.close();}finally{remove(path);}
            }
            if(handle.rebuild||handle.current==null){if(handle.building==null)createBuild(handle,sources,state);handle.rebuild=false;}
            if(handle.building!=null){
                final NotesSearchReplay.Step step;
                try{step=handle.building.advance(BATCH);}catch(NotesLuceneIndex.RebuildRequired expired){handle.building.close();remove(handle.buildingPath);handle.building=null;createBuild(handle,sources,state);return;}
                catch(ApiFailure sourceFailure){throw sourceFailure;}
                catch(IOException|RuntimeException broken){var previous=handle.building;var path=handle.buildingPath;handle.building=null;handle.buildingPath=null;handle.rebuild=true;try{previous.close();}finally{remove(path);}throw broken;}
                if(step.ready()){
                    var previous=handle.current;var previousPath=handle.currentPath;handle.current=handle.building;handle.currentPath=handle.buildingPath;handle.building=null;handle.buildingPath=null;handle.broken=false;
                    if(previous!=null){previous.close();remove(previousPath);}
                }
            }
            handle.error=null;
        }catch(ApiFailure failure){if(failure.status==404){retire(handle,true);return;}fail(handle,"SOURCE_UNAVAILABLE");}
        catch(IOException|RuntimeException failure){fail(handle,"CACHE_UNAVAILABLE");}
    }
    /** Checks free-space headroom before each batch and measures the soft cache limit every two seconds. */
    private boolean capacity()throws IOException{
        if(Files.getFileStore(root).getUsableSpace()<minimumFreeBytes)return false;
        if(System.nanoTime()>=measureAt){
            measuredBytes=size(root);measureAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            if(measuredBytes>=maximumBytes){
                Set<Path> active;synchronized(this){active=new HashSet<>();handles.values().forEach(handle->active.add(handle.folder));closing.forEach(handle->active.add(handle.folder));}
                List<Path> inactive;try(var folders=Files.list(root)){inactive=folders.filter(path->path.getFileName().toString().matches("[0-9a-f]{64}")&&!active.contains(path)).sorted(Comparator.comparingLong(NotesSearchService::modified)).toList();}
                for(var folder:inactive){long bytes=size(folder);remove(folder);measuredBytes-=bytes;if(measuredBytes<maximumBytes)break;}
            }
        }
        return measuredBytes<maximumBytes;
    }
    private static long modified(Path path){try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis();}catch(IOException unavailable){return Long.MIN_VALUE;}}
    private static long size(Path folder)throws IOException{
        long[] bytes={0};Files.walkFileTree(folder,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{
                if(attributes.isSymbolicLink())throw new IOException("Unexpected symbolic link in Notes search cache");
                bytes[0]=Math.addExact(bytes[0],attributes.size());return FileVisitResult.CONTINUE;
            }
        });return bytes[0];
    }
    private void createBuild(Handle handle,NotesSearchSources sources,NotesSearchSources.State state)throws IOException{
        handle.buildingPath=handle.folder.resolve("index-"+UUID.randomUUID());handle.building=handle.backend.open(handle.buildingPath,sources,state);
    }
    private void discover(Handle handle,NotesSearchSources sources,NotesSearchSources.State state)throws IOException{
        List<Path> folders;try(var entries=Files.list(handle.folder)){folders=entries.filter(path->path.getFileName().toString().matches("index-[0-9a-f-]{36}")).sorted().toList();}
        for(var path:folders){
            NotesIndexReplay replay;
            try{replay=handle.backend.open(path,sources,state);}catch(org.apache.lucene.store.LockObtainFailedException busy){throw busy;}catch(IOException|RuntimeException corrupt){remove(path);continue;}
            var saved=replay.index().watermark();boolean ready=saved.ready()&&saved.generation()<=state.changes().generation();
            var previous=ready?handle.current:handle.building;
            if(previous==null||saved.generation()>=previous.index().watermark().generation()){
                if(previous!=null){previous.close();remove(ready?handle.currentPath:handle.buildingPath);}
                if(ready){handle.current=replay;handle.currentPath=path;}else{handle.building=replay;handle.buildingPath=path;}
            }else{replay.close();remove(path);}
        }
    }
    private void fail(Handle handle,String code){handle.error=code;handle.retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);LOG.warnf("Notes search cache unavailable code=%s",code);}
    private void sweep()throws IOException{
        Set<String> registered;synchronized(this){registered=Set.copyOf(backends);}
        var allowed=new HashSet<String>();for(String owner:store.notesSearchOwners())for(String backend:registered)allowed.add(ownerKey(owner,backend));
        List<Handle> snapshot;synchronized(this){snapshot=List.copyOf(handles.values());}
        for(var handle:snapshot)if(!allowed.contains(handle.key))retire(handle,true);
        Set<Path> active;synchronized(this){active=new HashSet<>();handles.values().forEach(handle->active.add(handle.folder));}
        try(var folders=Files.list(root)){for(var folder:folders.toList())if(folder.getFileName().toString().matches("[0-9a-f]{64}")&&!allowed.contains(folder.getFileName().toString())&&!active.contains(folder))remove(folder);}
    }
    private void retire(Handle handle,boolean purge){synchronized(this){if(handles.get(handle.key)==handle)handles.remove(handle.key);handle.removed=true;closingOwners.add(handle.key);}try{closeHandle(handle,purge);}catch(IOException failure){LOG.warn("Notes search cache cleanup unavailable");}finally{synchronized(this){closingOwners.remove(handle.key);}}}
    private static void closeHandle(Handle handle,boolean purge)throws IOException{
        IOException failure=null;
        try{if(handle.current!=null)handle.current.close();}catch(IOException problem){failure=problem;}
        try{if(handle.building!=null)handle.building.close();}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        if(purge)try{remove(handle.folder);}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        if(failure!=null)throw failure;
    }
    private static void remove(Path folder)throws IOException{
        if(folder==null||!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(folder,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });
    }
    @Override @jakarta.annotation.PreDestroy public void close()throws IOException{
        ScheduledExecutorService running;synchronized(this){if(closed)return;closed=true;running=worker;}
        if(running!=null){running.shutdownNow();try{if(!running.awaitTermination(5,TimeUnit.SECONDS)){LOG.warn("Notes search worker exceeded shutdown deadline");return;}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}
        IOException failure=null;var all=new ArrayList<>(handles.values());all.addAll(closing);
        for(var handle:all)try{closeHandle(handle,false);}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        try{if(fileLock!=null)fileLock.close();}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        try{if(channel!=null)channel.close();}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        try{if(temporary&&root!=null)remove(root);}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        if(failure!=null)throw failure;
    }
}
