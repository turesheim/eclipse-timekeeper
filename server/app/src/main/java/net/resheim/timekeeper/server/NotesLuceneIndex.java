package net.resheim.timekeeper.server;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;

/** One replica's private owner index. Inputs must come from committed SQL snapshots. */
final class NotesLuceneIndex implements AutoCloseable {
    static final String FORMAT="notes-search-1";
    static final int CHUNK=65_536,EXCERPT=320;
    private static final Sort ORDER=new Sort(SortField.FIELD_SCORE,new SortField("path_key",SortField.Type.STRING),new SortField("id",SortField.Type.STRING));
    private static final FieldType BODY;
    private static final Set<String> METADATA=Set.of("id","path","kind","title","source_type","source_id","source_version","source_tag","source_hash","body_size","path_key");
    static{BODY=new FieldType(TextField.TYPE_NOT_STORED);BODY.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);BODY.freeze();}
    record Identity(String owner,UUID collection,UUID epoch,String restoreGeneration){
        Identity{Objects.requireNonNull(owner);Objects.requireNonNull(collection);Objects.requireNonNull(epoch);restoreGeneration=restoreGeneration==null?"initial":restoreGeneration;}
    }
    record Watermark(long generation,boolean ready,UUID afterEntry){
        Watermark{if(generation<0||generation>9007199254740991L)throw new IllegalArgumentException("Invalid search watermark");}
    }
    record Source(UUID id,String path,String kind,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,long byteLength,String title,NotesSearchText.Projection text){
        @Override public String toString(){return "NotesLuceneIndex.Source[id="+id+"]";}
    }
    record Position(float score,String pathKey,UUID id){
        Position{if(!Float.isFinite(score)||score<0)throw new IllegalArgumentException("Invalid search position");Objects.requireNonNull(pathKey);Objects.requireNonNull(id);}
        @Override public String toString(){return "NotesLuceneIndex.Position[]";}
    }
    record Range(int start,int end){}
    record Excerpt(String text,List<Range> highlights){
        Excerpt{highlights=List.copyOf(highlights);}
        @Override public String toString(){return "NotesLuceneIndex.Excerpt[length="+text.length()+"]";}
    }
    record Hit(UUID id,String path,String kind,String title,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,Excerpt excerpt,Position position){
        @Override public String toString(){return "NotesLuceneIndex.Hit[id="+id+"]";}
    }
    record Page(List<Hit> items,Position next){Page{items=List.copyOf(items);}}
    static final class RebuildRequired extends IOException{private static final long serialVersionUID=1L;RebuildRequired(){super("Notes search cache must be rebuilt");}}
    static final class Unavailable extends IOException{private static final long serialVersionUID=1L;Unavailable(){super("Notes search is not ready");}}
    private final Identity identity;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager readers;
    private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock();
    private volatile Watermark watermark;
    private boolean closed;

    NotesLuceneIndex(Path path,Identity identity)throws IOException{this(path,identity,new Watermark(0,false,null));}
    NotesLuceneIndex(Path path,Identity identity,Watermark initial)throws IOException{
        this.identity=identity;privateDirectory(path);
        Directory opened=FSDirectory.open(path);Analyzer analysis=NotesSearchQuery.analyzer();IndexWriter writing=null;SearcherManager managed=null;
        try{
            Watermark saved=initial;
            if(DirectoryReader.indexExists(opened))try(var reader=DirectoryReader.open(opened)){
                var data=reader.getIndexCommit().getUserData();
                for(var expected:identityData().entrySet())if(!expected.getValue().equals(data.get(expected.getKey())))throw new RebuildRequired();
                try{
                    if(!Set.of("true","false").contains(data.get("ready")))throw new IllegalArgumentException();
                    saved=new Watermark(Long.parseLong(data.get("generation")),Boolean.parseBoolean(data.get("ready")),data.get("after").isEmpty()?null:UUID.fromString(data.get("after")));
                }
                catch(RuntimeException invalid){throw new RebuildRequired();}
            }
            writing=new IndexWriter(opened,new IndexWriterConfig(analysis).setCommitOnClose(false).setRAMBufferSizeMB(16).setMergeScheduler(new SerialMergeScheduler()));
            writing.setLiveCommitData(metadata(saved).entrySet());writing.commit();managed=new SearcherManager(opened,null);
            for(String name:opened.listAll())privateFile(path.resolve(name));
            directory=opened;analyzer=analysis;writer=writing;readers=managed;watermark=saved;
        }catch(IOException|RuntimeException failure){
            if(managed!=null)managed.close();if(writing!=null)writing.rollback();analysis.close();opened.close();throw failure;
        }
    }
    private Map<String,String> identityData(){return Map.of("format",FORMAT,"lucene",org.apache.lucene.util.Version.LATEST.toString(),"owner",identity.owner(),"collection",identity.collection().toString(),"epoch",identity.epoch().toString(),"restore",identity.restoreGeneration());}
    private Map<String,String> metadata(Watermark state){var values=new HashMap<>(identityData());values.put("generation",Long.toString(state.generation()));values.put("ready",Boolean.toString(state.ready()));values.put("after",state.afterEntry()==null?"":state.afterEntry().toString());return values;}
    Watermark watermark(){return watermark;}
    Identity identity(){return identity;}
    void put(Source source)throws IOException{
        if(!Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(source.kind()))throw new IllegalArgumentException("Unsupported search source");
        var document=new Document();String id=source.id().toString(),pathKey=VaultPath.searchKey(source.path());
        document.add(new StringField("id",id,Field.Store.YES));document.add(new SortedDocValuesField("id",new BytesRef(id)));
        document.add(new StringField("owner",identity.owner(),Field.Store.NO));document.add(new StringField("deleted","false",Field.Store.NO));
        document.add(new StringField("kind",source.kind(),Field.Store.YES));document.add(new StringField("configuration",Boolean.toString(VaultSettings.configurationPath(new VaultPath(source.path()))),Field.Store.NO));
        document.add(new StringField("path_key",pathKey,Field.Store.YES));document.add(new SortedDocValuesField("path_key",new BytesRef(pathKey)));
        document.add(new StoredField("path",source.path()));document.add(new TextField("path",NotesSearchText.normalized(source.path()),Field.Store.NO));
        String leaf=new VaultPath(source.path()).name();if(leaf.toLowerCase(Locale.ROOT).endsWith(".md"))leaf=leaf.substring(0,leaf.length()-3);
        document.add(new TextField("path",NotesSearchText.normalized(leaf),Field.Store.NO));
        document.add(new TextField("title",NotesSearchText.normalized(source.title()),Field.Store.YES));
        document.add(new StoredField("source_type",source.sourceType()));document.add(new StoredField("source_id",source.sourceId().toString()));
        document.add(new StoredField("source_version",source.sourceVersion()));document.add(new StoredField("source_tag",source.sourceTag()));document.add(new StoredField("source_hash",source.sourceHash()));
        for(String block:source.text().blocks())document.add(new Field("body",block,BODY));
        String body=source.text().body();document.add(new StoredField("body_size",body.length()));
        // Stored chunks bound highlight reads even for a maximum-size document.
        // Overlap retains UTF-16 pairs without changing indexed offset positions.
        for(int start=0,number=0;start<body.length();start+=CHUNK,number++){
            int from=start>0&&Character.isLowSurrogate(body.charAt(start))?start-1:start,to=Math.min(body.length(),start+CHUNK);
            if(to<body.length()&&Character.isHighSurrogate(body.charAt(to-1)))to++;
            byte[] text=body.substring(from,to).getBytes(StandardCharsets.UTF_8),payload=new byte[text.length+4];
            ByteBuffer.wrap(payload).putInt(from).put(text);document.add(new StoredField(chunk(number),payload));
        }
        lock.writeLock().lock();try{open();writer.updateDocument(new Term("id",id),document);}finally{lock.writeLock().unlock();}
    }
    void remove(UUID id)throws IOException{lock.writeLock().lock();try{open();writer.deleteDocuments(new Term("id",id.toString()));}finally{lock.writeLock().unlock();}}
    void commit(Watermark state)throws IOException{
        lock.writeLock().lock();try{
            open();writer.setLiveCommitData(metadata(state).entrySet());writer.commit();readers.maybeRefreshBlocking();watermark=state;
            for(String name:directory.listAll())privateFile(((FSDirectory)directory).getDirectory().resolve(name));
        }finally{lock.writeLock().unlock();}
    }
    Page search(NotesSearchQuery.Parsed parsed,long expectedGeneration,int limit,Position after,boolean configuration,String hiddenJournal,long milliseconds)throws IOException{
        if(limit<1||limit>200||milliseconds<1||milliseconds>5000)throw new IllegalArgumentException("Invalid search budget");
        long started=System.nanoTime(),budget=TimeUnit.MILLISECONDS.toNanos(milliseconds);boolean acquired;
        try{acquired=lock.readLock().tryLock(milliseconds,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable();}
        if(!acquired)throw new Unavailable();IndexSearcher held=null;
        try{
            open();if(!watermark.ready()||watermark.generation()!=expectedGeneration)throw new Unavailable();
            held=readers.acquire();var searcher=new IndexSearcher(held.getIndexReader());searcher.setTimeout(()->System.nanoTime()-started>budget);
            Query query=NotesSearchQuery.scoped(parsed,identity.owner(),configuration,hiddenJournal);
            if(searcher.getIndexReader().maxDoc()==0)return new Page(List.of(),null);
            // The public position is score/path/UUID. The local maximum doc ID
            // only excludes the exact unique tuple and is never serialized.
            FieldDoc cursor=after==null?null:new FieldDoc(searcher.getIndexReader().maxDoc()-1,after.score(),new Object[]{after.score(),new BytesRef(after.pathKey()),new BytesRef(after.id().toString())});
            var hits=searcher.searchAfter(cursor,query,limit+1,ORDER,true);if(searcher.timedOut())throw new Unavailable();
            var weight=searcher.createWeight(searcher.rewrite(query),ScoreMode.COMPLETE_NO_SCORES,1);
            var result=new ArrayList<Hit>();
            for(int index=0;index<Math.min(limit,hits.scoreDocs.length);index++){
                if(System.nanoTime()-started>budget)throw new Unavailable();
                var hit=(FieldDoc)hits.scoreDocs[index];var values=searcher.storedFields().document(hit.doc,METADATA);
                UUID id=UUID.fromString(values.get("id"));var position=new Position(((Number)hit.fields[0]).floatValue(),values.get("path_key"),id);
                int leaf=ReaderUtil.subIndex(hit.doc,searcher.getIndexReader().leaves());var context=searcher.getIndexReader().leaves().get(leaf);
                var matched=weight.matches(context,hit.doc-context.docBase);var matches=matched==null?null:matched.getMatches("body");
                var excerpt=excerpt(searcher,hit.doc,values.getField("body_size").numericValue().intValue(),matches);
                result.add(new Hit(id,values.get("path"),values.get("kind"),values.get("title"),values.get("source_type"),UUID.fromString(values.get("source_id")),values.getField("source_version").numericValue().longValue(),values.get("source_tag"),values.get("source_hash"),excerpt,position));
            }
            if(System.nanoTime()-started>budget)throw new Unavailable();
            return new Page(result,hits.scoreDocs.length>limit?result.getLast().position():null);
        }finally{if(held!=null)readers.release(held);lock.readLock().unlock();}
    }
    private static Excerpt excerpt(IndexSearcher searcher,int document,int size,MatchesIterator matches)throws IOException{
        if(size==0)return new Excerpt("",List.of());
        boolean found=matches!=null&&matches.next();int anchor=found?Math.max(0,matches.startOffset()):0;
        int start=Math.max(0,anchor-80),end=Math.min(size,start+EXCERPT);if(end==size)start=Math.max(0,end-EXCERPT);
        int first=start/CHUNK,last=(end-1)/CHUNK;var names=new HashSet<String>();for(int i=first;i<=last;i++)names.add(chunk(i));
        var values=searcher.storedFields().document(document,names);var joined=new StringBuilder();int base=-1;
        for(int i=first;i<=last;i++){
            BytesRef bytes=values.getBinaryValue(chunk(i));if(bytes==null||bytes.length<4)throw new RebuildRequired();
            int offset=ByteBuffer.wrap(bytes.bytes,bytes.offset,4).getInt();String text=new String(bytes.bytes,bytes.offset+4,bytes.length-4,StandardCharsets.UTF_8);
            if(base<0){base=offset;joined.append(text);}else{int overlap=base+joined.length()-offset;if(overlap<0||overlap>text.length())throw new RebuildRequired();joined.append(text.substring(overlap));}
        }
        int local=start-base,to=end-base;if(local<0||to>joined.length())throw new RebuildRequired();
        if(local>0&&Character.isLowSurrogate(joined.charAt(local)))local--;
        to=Math.min(to,local+EXCERPT);if(to<joined.length()&&to>local&&Character.isHighSurrogate(joined.charAt(to-1)))to--;
        start=base+local;end=base+to;var ranges=new ArrayList<Range>();
        while(found&&matches.startOffset()<end&&ranges.size()<64){
            int from=Math.max(start,matches.startOffset()),until=Math.min(end,matches.endOffset());
            if(from<until){var range=new Range(from-start,until-start);if(!ranges.isEmpty()&&range.start()<=ranges.getLast().end()){var previous=ranges.removeLast();range=new Range(previous.start(),Math.max(previous.end(),range.end()));}ranges.add(range);}
            found=matches.next();
        }
        return new Excerpt(joined.substring(local,to),ranges);
    }
    private static String chunk(int index){return "body_"+index;}
    private void open()throws IOException{if(closed)throw new Unavailable();}
    static void privateDirectory(Path path)throws IOException{
        if(Files.isSymbolicLink(path))throw new IOException("Invalid private search cache");
        try{Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}
        catch(UnsupportedOperationException unsupported){
            Files.createDirectories(path);var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);if(acl==null)throw new IOException("Private search cache permissions unavailable");
            var owner=Files.getOwner(path);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
        }
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid private search cache");
    }
    static void privateFile(Path path)throws IOException{try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException unsupported){/* Inherit the owner-only directory ACL. */}}
    @Override public void close()throws IOException{
        lock.writeLock().lock();try{
            if(closed)return;closed=true;
            try{readers.close();}finally{try{writer.rollback();}finally{try{directory.close();}finally{analyzer.close();}}}
        }finally{lock.writeLock().unlock();}
    }
}
