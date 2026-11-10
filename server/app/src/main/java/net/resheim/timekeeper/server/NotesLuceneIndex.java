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
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One replica's private owner index. Inputs must come from committed SQL snapshots. */
final class NotesLuceneIndex implements AutoCloseable {
    static final String FORMAT="notes-search-3";
    static final int CHUNK=65_536,EXCERPT=320;
    private static final Sort ORDER=new Sort(SortField.FIELD_SCORE,new SortField("path_key",SortField.Type.STRING),new SortField("id",SortField.Type.STRING));
    private static final FieldType BODY;
    private static final Set<String> METADATA=Set.of("id","path","kind","title","source_type","source_id","source_version","source_tag","source_hash","body_size","path_key","calendar");
    static{BODY=new FieldType(TextField.TYPE_NOT_STORED);BODY.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);BODY.freeze();}
    record Identity(String owner,UUID collection,UUID epoch,String restoreGeneration){
        Identity{Objects.requireNonNull(owner);Objects.requireNonNull(collection);Objects.requireNonNull(epoch);restoreGeneration=restoreGeneration==null?"initial":restoreGeneration;}
    }
    record Watermark(long generation,boolean ready,UUID afterEntry){
        Watermark{if(generation<0||generation>9007199254740991L)throw new IllegalArgumentException("Invalid search watermark");}
    }
    record Source(UUID id,String path,String kind,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,long byteLength,String title,NotesSearchText.Projection text,ObjectNode calendar){
        Source(UUID id,String path,String kind,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,long byteLength,String title,NotesSearchText.Projection text){this(id,path,kind,sourceType,sourceId,sourceVersion,sourceTag,sourceHash,byteLength,title,text,null);}
        Source{if(calendar!=null)calendar=calendar.deepCopy();}
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
    record Hit(UUID id,String path,String kind,String title,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,Excerpt excerpt,Position position,ObjectNode calendar){
        Hit(UUID id,String path,String kind,String title,String sourceType,UUID sourceId,long sourceVersion,String sourceTag,String sourceHash,Excerpt excerpt,Position position){this(id,path,kind,title,sourceType,sourceId,sourceVersion,sourceTag,sourceHash,excerpt,position,null);}
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
    private final Map<String,Set<String>> sourceValues=new java.util.concurrent.ConcurrentHashMap<>();
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
        document.add(new StringField("source_type",source.sourceType(),Field.Store.YES));document.add(new StoredField("source_id",source.sourceId().toString()));
        if(source.calendar()!=null){String value=source.calendar().toString();if(value.length()>262144)throw new IllegalArgumentException("Calendar search metadata exceeds its bound");document.add(new StoredField("calendar",value));}
        document.add(new StoredField("source_version",source.sourceVersion()));document.add(new StoredField("source_tag",source.sourceTag()));document.add(new StoredField("source_hash",source.sourceHash()));
        String fence=source.calendar()!=null&&source.calendar().hasNonNull("noteFence")?source.calendar().path("noteFence").asText():fence(source);
        document.add(new StringField("source_fence",fence,Field.Store.YES));
        document.add(new SortedDocValuesField("source_fence",new BytesRef(fence)));
        for(String block:source.text().blocks())document.add(new Field("body",block,BODY));
        for(String prose:source.text().tags())document.add(new TextField("tag",prose,Field.Store.NO));
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
            open();writer.setLiveCommitData(metadata(state).entrySet());writer.commit();if(state.ready())readers.maybeRefreshBlocking();watermark=state;sourceValues.clear();
            for(String name:directory.listAll())privateFile(((FSDirectory)directory).getDirectory().resolve(name));
        }finally{lock.writeLock().unlock();}
    }
    Page search(NotesSearchQuery.Parsed parsed,long expectedGeneration,int limit,Position after,boolean configuration,String hiddenJournal,long milliseconds)throws IOException{
        return search(parsed,expectedGeneration,limit,after,configuration,hiddenJournal,milliseconds,Set.of());
    }
    Page search(NotesSearchQuery.Parsed parsed,long expectedGeneration,int limit,Position after,boolean configuration,String hiddenJournal,long milliseconds,Set<String> excluded)throws IOException{
        if(limit<1||limit>200||milliseconds<1||milliseconds>5000)throw new IllegalArgumentException("Invalid search budget");
        long started=System.nanoTime(),budget=TimeUnit.MILLISECONDS.toNanos(milliseconds);boolean acquired;
        try{acquired=lock.readLock().tryLock(milliseconds,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable();}
        if(!acquired)throw new Unavailable();IndexSearcher held=null;
        try{
            open();if(!watermark.ready()||watermark.generation()!=expectedGeneration)throw new Unavailable();
            held=readers.acquire();var searcher=new IndexSearcher(held.getIndexReader());searcher.setTimeout(()->System.nanoTime()-started>budget);
            Query query=NotesSearchQuery.scoped(parsed,identity.owner(),configuration,hiddenJournal);
            if(!excluded.isEmpty())query=new BooleanQuery.Builder().add(query,BooleanClause.Occur.MUST).add(new TermInSetQuery("source_fence",excluded.stream().map(BytesRef::new).toList()),BooleanClause.Occur.MUST_NOT).build();
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
                result.add(hit(values,excerpt,position));
            }
            if(System.nanoTime()-started>budget)throw new Unavailable();
            return new Page(result,hits.scoreDocs.length>limit?result.getLast().position():null);
        }finally{if(held!=null)readers.release(held);lock.readLock().unlock();}
    }
    private static Hit hit(Document values,Excerpt excerpt,Position position)throws IOException{
        ObjectNode calendar=null;String encoded=values.get("calendar");
        if(encoded!=null){if(encoded.length()>262144)throw new RebuildRequired();var parsed=JsonValues.parse(encoded);if(!parsed.isObject())throw new RebuildRequired();calendar=(ObjectNode)parsed;}
        return new Hit(UUID.fromString(values.get("id")),values.get("path"),values.get("kind"),values.get("title"),values.get("source_type"),UUID.fromString(values.get("source_id")),values.getField("source_version").numericValue().longValue(),values.get("source_tag"),values.get("source_hash"),excerpt,position,calendar);
    }
    static String fence(Source source){
        return JsonValues.etag(JsonValues.array().add(source.id().toString()).add(source.path()).add(source.kind()).add(source.sourceType()).add(source.sourceId().toString()).add(source.sourceVersion()).add(source.sourceTag()).add(source.sourceHash()).add(source.title()));
    }
    Set<String> sourceFences(String type,long expectedGeneration)throws IOException{return sourceValues(type,"source_fence",expectedGeneration);}
    Set<UUID> sourceIds(String type,long expectedGeneration)throws IOException{return sourceValues(type,"id",expectedGeneration).stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private Set<String> sourceValues(String type,String field,long expectedGeneration)throws IOException{
        readLock();IndexSearcher searcher=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        try{
            open();if(!watermark.ready()||watermark.generation()!=expectedGeneration)throw new Unavailable();String key=type+":"+field;var cached=sourceValues.get(key);if(cached!=null)return cached;
            searcher=readers.acquire();var result=new HashSet<String>();var reader=searcher.getIndexReader();
            for(var leaf:reader.leaves()){
                var postings=leaf.reader().postings(new Term("source_type",type));if(postings==null)continue;var live=leaf.reader().getLiveDocs();var values=DocValues.getSorted(leaf.reader(),field);
                for(int doc=postings.nextDoc();doc!=org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;doc=postings.nextDoc())if(live==null||live.get(doc)){
                    if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new Unavailable();
                    if(!values.advanceExact(doc))throw new RebuildRequired();result.add(values.lookupOrd(values.ordValue()).utf8ToString());
                }
            }
            var values=Set.copyOf(result);sourceValues.put(key,values);return values;
        }finally{if(searcher!=null)readers.release(searcher);lock.readLock().unlock();}
    }
    Hit lookup(UUID id,long expectedGeneration,boolean configuration,String hiddenJournal)throws IOException{
        readLock();IndexSearcher searcher=null;
        try{
            open();if(!watermark.ready()||watermark.generation()!=expectedGeneration)throw new Unavailable();searcher=readers.acquire();
            var query=NotesSearchQuery.scoped(new NotesSearchQuery.Parsed("",new TermQuery(new Term("id",id.toString()))),identity.owner(),configuration,hiddenJournal);
            var hits=searcher.search(query,1);if(hits.scoreDocs.length==0)return null;
            int doc=hits.scoreDocs[0].doc;var values=searcher.storedFields().document(doc,METADATA);
            return hit(values,excerpt(searcher,doc,values.getField("body_size").numericValue().intValue(),null),new Position(0,values.get("path_key"),id));
        }finally{if(searcher!=null)readers.release(searcher);lock.readLock().unlock();}
    }
    private void readLock()throws IOException{
        try{if(!lock.readLock().tryLock(1,TimeUnit.SECONDS))throw new Unavailable();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable();}
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
