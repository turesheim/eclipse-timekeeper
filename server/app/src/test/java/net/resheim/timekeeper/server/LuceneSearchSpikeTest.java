package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.uhighlight.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.QueryBuilder;

/**
 * Engine/API spike, before production replay and API integration. Synthetic SQL
 * fixtures model committed canonical state; this is not the application's
 * mutation/outbox implementation or its replica acceptance test.
 */
class LuceneSearchSpikeTest {
    @TempDir Path temporary;
    private static final Parser MARKDOWN=Parser.builder().extensions(List.of(
            YamlFrontMatterExtension.create(),StrikethroughExtension.create())).build();
    private static final TextContentRenderer TEXT=TextContentRenderer.builder().build();
    private static final FieldType BODY;
    static {
        BODY=new FieldType(TextField.TYPE_STORED);
        BODY.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);BODY.freeze();
    }
    private static String normalized(String value){return Normalizer.normalize(value,Normalizer.Form.NFC);}
    private static String body(String source){
        var tree=MARKDOWN.parse(source.startsWith("\ufeff")?source.substring(1):source);
        for(var block=tree.getFirstChild();block!=null;){var next=block.getNext();if(block instanceof org.commonmark.ext.front.matter.YamlFrontMatterBlock)block.unlink();block=next;}
        return normalized(TEXT.render(tree));
    }
    record Note(String id,String path,String title,String markdown) {
        static Note of(String path,String title,String markdown){return new Note(UUID.randomUUID().toString(),path,title,markdown);}
        Note edit(String value){return new Note(id,path,title,value);}
    }

    @Test void bodyPhrasesUnicodeTechnicalTermsAndTitleBoostUseThePinnedEngine()throws Exception{
        try(var sql=new Canonical(h2());var replica=new Replica(sql,"alice",temporary.resolve("index"))){
            var title=Note.of("Notes/Title.md","OpenShift troubleshooting","Other content.");
            var content=Note.of("Notes/Body.md","Unrelated title","---\nprivate-only: hiddenfrontmattertoken\n---\n\n# Café\n\nFeilsøking av køer og blåbær.\n\n~~~shell\nkubectl exec dnsutils -- nslookup service.cluster.local\n~~~\n\nOpenShift troubleshooting needs exact phrase matching.");
            sql.save("alice",title,false,true);sql.save("alice",content,false,true);replica.replay();
            assertEquals(List.of(content.id),replica.ids(replica.phrase("exact phrase")));
            assertEquals(List.of(content.id),replica.ids(replica.words("FEILSØKING BLÅBÆR")));
            assertEquals(List.of(content.id),replica.ids(replica.words("Cafe\u0301")));
            assertEquals(List.of(content.id),replica.ids(replica.words("kubectl service.cluster.local")));
            assertTrue(replica.ids(replica.words("hiddenfrontmattertoken")).isEmpty());
            assertEquals(title.id,replica.ids(replica.words("OpenShift troubleshooting")).getFirst());
            var excerpt=replica.excerpt(replica.phrase("exact phrase"));
            assertTrue(excerpt.contains("<b>exact phrase</b>"),excerpt);
            assertEquals(content.markdown,sql.read("alice",content.id).markdown);
        }
    }

    @Test void committedUpdateDeleteRestartOfflineReplicaAndRebuildConvergeOnH2()throws Exception{
        replicas(h2());
    }
    @Test void committedUpdateDeleteRestartOfflineReplicaAndRebuildConvergeOnPostgres()throws Exception{
        String url=fixture("TIMEKEEPER_TEST_LUCENE_DB_URL","TIMEKEEPER_TEST_PG_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url!=null&&!url.isBlank(),"PostgreSQL spike fixture not configured");
        replicas(url);
    }
    private void replicas(String url)throws Exception{
        try(var sql=new Canonical(url)){
            Path first=temporary.resolve("replica-a"),second=temporary.resolve("replica-b");
            var note=Note.of("Notes/Local.md","Local","Original committed content.");
            var foreign=Note.of("Notes/Foreign.md","Foreign","Foreign owner secret.");
            sql.save("alice",note,false,true);sql.save("bob",foreign,false,true);
            try(var a=new Replica(sql,"alice",first);var b=new Replica(sql,"alice",second)){
                a.replay();b.replay();assertEquals(a.ids(a.words("original")),b.ids(b.words("original")));
                assertTrue(a.ids(a.words("foreign secret")).isEmpty());
                long generation=a.watermark;
                sql.save("alice",note.edit("Rejected replacement."),false,false);
                a.replay();assertEquals(generation,a.watermark);assertEquals(List.of(note.id),a.ids(a.words("original")));
                // SQL commits while replica B is offline; A catches up alone.
                sql.save("alice",note.edit("Updated committed body."),false,true);
                a.replay();assertEquals(List.of(note.id),a.ids(a.words("updated")));assertTrue(a.ids(a.words("original")).isEmpty());
                assertEquals(List.of(note.id),b.ids(b.words("original")));
            }
            // A process restart recovers its index commit watermark. B replays
            // the SQL/index gap rather than depending on an after-commit callback.
            try(var a=new Replica(sql,"alice",first);var b=new Replica(sql,"alice",second)){
                b.replay();assertEquals(a.watermark,b.watermark);assertEquals(List.of(note.id),b.ids(b.words("updated")));
                sql.save("alice",note,true,true);a.replay();assertTrue(a.ids(a.words("updated")).isEmpty());
            }
            try(var b=new Replica(sql,"alice",second)){b.replay();assertTrue(b.ids(b.words("updated")).isEmpty());}
            sql.save("alice",note.edit("Recovered solely from SQL."),false,true);
            try(var paths=Files.walk(first)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
            try(var rebuilt=new Replica(sql,"alice",first)){rebuilt.replay();assertEquals(List.of(note.id),rebuilt.ids(rebuilt.words("recovered solely")));}
        }
    }

    @Test void aMatchNearTheEndOfDefaultMaximumMarkdownIsIndexedAndHighlighted()throws Exception{
        // The highlighter's default character cap must not hide a valid hit.
        String filler="ordinary filler text.\n",ending="\nterminalneedle unique ending.\n";
        String source=filler.repeat((10_000_000-ending.length())/filler.length());
        source+=" ".repeat(10_000_000-source.length()-ending.length())+ending;
        assertEquals(10_000_000,source.length());
        try(var sql=new Canonical(h2());var replica=new Replica(sql,"alice",temporary.resolve("large"))){
            var note=Note.of("Notes/Large.md","Large",source);sql.save("alice",note,false,true);replica.replay();
            assertEquals(List.of(note.id),replica.ids(replica.words("terminalneedle")));
            assertTrue(replica.excerpt(replica.words("terminalneedle")).contains("<b>terminalneedle</b>"));
        }
    }
    @Test void recordSyntheticCorpusBuildQueryAndDiskMeasurements()throws Exception{
        long requested=Long.getLong("timekeeper.lucene.spike.bytes",8_000_000L);
        assertTrue(requested>0&&requested<=5_000_000_000L);
        long corpusBytes=0;int documents=0;Path index=temporary.resolve("measured");
        try(var sql=new Canonical(h2());var replica=new Replica(sql,"alice",index)){
            while(corpusBytes<requested){
                var note=Note.of("Notes/Document-"+documents+".md","Service record "+documents,syntheticMarkdown(documents));
                long previous=corpusBytes;sql.save("alice",note,false,true);corpusBytes+=note.markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;documents++;
                if(corpusBytes/1_000_000_000>previous/1_000_000_000)System.out.println("Lucene spike canonical bytes prepared: "+corpusBytes);
            }
            long heapBefore=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            System.out.println("Lucene spike indexing "+documents+" synthetic documents");
            long start=System.nanoTime();replica.replay();long buildNanos=System.nanoTime()-start;
            long heapAfter=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            var query=replica.phrase("cache misses");for(int i=0;i<5;i++)assertFalse(replica.ids(query).isEmpty());
            var timings=new long[40];for(int i=0;i<timings.length;i++){start=System.nanoTime();assertFalse(replica.ids(query).isEmpty());timings[i]=System.nanoTime()-start;}Arrays.sort(timings);
            long indexBytes;try(var paths=Files.walk(index)){indexBytes=paths.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}}).sum();}
            var metrics=JsonValues.object().put("engine","Lucene "+org.apache.lucene.util.Version.LATEST).put("java",Runtime.version().toString())
                    .put("protocol","synthetic H2 corpus; FSDirectory; committed reader reopened per query")
                    .put("corpus","mixed English/Norwegian Markdown, lists, code, links and technical identifiers")
                    .put("documents",documents).put("canonicalBytes",corpusBytes).put("indexBytes",indexBytes)
                    .put("buildMillis",buildNanos/1_000_000).put("queryP50Micros",timings[19]/1000).put("queryP95Micros",timings[37]/1000)
                    .put("heapUsedBeforeIndexBytes",heapBefore).put("heapUsedAfterIndexBytes",heapAfter);
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/lucene-spike-metrics.json"),metrics.toPrettyString());
            System.out.println("Lucene spike measurements: "+metrics);
        }
    }
    private static String syntheticMarkdown(int number){return NotesSearchCorpus.markdown(number);}
    private String h2(){return "jdbc:h2:file:"+temporary.resolve("canonical").toAbsolutePath();}
    private static String fixture(String name,String fallback){String value=System.getenv(name);return value==null?System.getenv(fallback):value;}

    /** SQL history and canonical text are both committed in one transaction. */
    private static final class Canonical implements AutoCloseable {
        final String url,prefix="lucene_spike_"+UUID.randomUUID().toString().replace("-","");final Connection anchor;
        Canonical(String url)throws SQLException{
            this.url=url;anchor=connect();try(var statement=anchor.createStatement()){
                statement.execute("CREATE TABLE "+prefix+"_state(owner_id VARCHAR(80) PRIMARY KEY,generation BIGINT NOT NULL)");
                statement.execute("CREATE TABLE "+prefix+"_notes(owner_id VARCHAR(80),id VARCHAR(36),path VARCHAR(1000),title VARCHAR(1000),body TEXT,deleted BOOLEAN,PRIMARY KEY(owner_id,id))");
                statement.execute("CREATE TABLE "+prefix+"_changes(owner_id VARCHAR(80),generation BIGINT,id VARCHAR(36),PRIMARY KEY(owner_id,generation))");
            }
        }
        Connection connect()throws SQLException{
            var properties=new Properties();String user=fixture("TIMEKEEPER_TEST_LUCENE_DB_USER","TIMEKEEPER_TEST_PG_USER"),password=fixture("TIMEKEEPER_TEST_LUCENE_DB_PASSWORD","TIMEKEEPER_TEST_PG_PASSWORD");
            if(url.startsWith("jdbc:h2:")){properties.setProperty("user","sa");properties.setProperty("password","");}
            else {if(user!=null)properties.setProperty("user",user);if(password!=null)properties.setProperty("password",password);}
            return DriverManager.getConnection(url,properties);
        }
        void save(String owner,Note note,boolean deleted,boolean commit)throws SQLException{
            try(var c=connect()){c.setAutoCommit(false);
                try(var s=c.prepareStatement("UPDATE "+prefix+"_state SET generation=generation+1 WHERE owner_id=?")){
                    s.setString(1,owner);if(s.executeUpdate()==0)try(var insert=c.prepareStatement("INSERT INTO "+prefix+"_state VALUES(?,1)")){insert.setString(1,owner);insert.executeUpdate();}
                }
                long generation=generation(c,owner);
                try(var s=c.prepareStatement("DELETE FROM "+prefix+"_notes WHERE owner_id=? AND id=?")){s.setString(1,owner);s.setString(2,note.id);s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO "+prefix+"_notes VALUES(?,?,?,?,?,?)")){s.setString(1,owner);s.setString(2,note.id);s.setString(3,note.path);s.setString(4,note.title);s.setString(5,deleted?null:note.markdown);s.setBoolean(6,deleted);s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO "+prefix+"_changes VALUES(?,?,?)")){s.setString(1,owner);s.setLong(2,generation);s.setString(3,note.id);s.executeUpdate();}
                if(commit)c.commit();else c.rollback();
            }
        }
        long generation(Connection c,String owner)throws SQLException{
            try(var s=c.prepareStatement("SELECT generation FROM "+prefix+"_state WHERE owner_id=?")){s.setString(1,owner);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}
        }
        Note read(String owner,String id)throws SQLException{
            try(var c=connect();var s=c.prepareStatement("SELECT path,title,body FROM "+prefix+"_notes WHERE owner_id=? AND id=? AND deleted=FALSE")){s.setString(1,owner);s.setString(2,id);try(var r=s.executeQuery()){return r.next()?new Note(id,r.getString(1),r.getString(2),r.getString(3)):null;}}
        }
        public void close()throws SQLException{
            try {try(var c=connect();var s=c.createStatement()){for(var suffix:List.of("_changes","_notes","_state"))s.execute("DROP TABLE "+prefix+suffix);}}
            finally{anchor.close();}
        }
    }

    /** A private local index for one replica/owner; SQL owns the watermark. */
    private static final class Replica implements AutoCloseable {
        final Canonical sql;final String owner;final Directory directory;final StandardAnalyzer analyzer=new StandardAnalyzer();final IndexWriter writer;
        long watermark;
        Replica(Canonical sql,String owner,Path path)throws IOException{
            this.sql=sql;this.owner=owner;Files.createDirectories(path);directory=FSDirectory.open(path);
            if(DirectoryReader.indexExists(directory))try(var reader=DirectoryReader.open(directory)){var metadata=reader.getIndexCommit().getUserData();assertEquals(owner,metadata.get("owner"));watermark=Long.parseLong(metadata.get("generation"));}
            writer=new IndexWriter(directory,new IndexWriterConfig(analyzer));commit(watermark);
        }
        void replay()throws Exception{
            try(var c=sql.connect()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);long generation=sql.generation(c,owner);
                try(var s=c.prepareStatement("SELECT DISTINCT id FROM "+sql.prefix+"_changes WHERE owner_id=? AND generation>? AND generation<=?")){
                    s.setString(1,owner);s.setLong(2,watermark);s.setLong(3,generation);
                    try(var ids=s.executeQuery()){while(ids.next()){
                        String id=ids.getString(1);
                        try(var read=c.prepareStatement("SELECT path,title,body,deleted FROM "+sql.prefix+"_notes WHERE owner_id=? AND id=?")){
                            read.setString(1,owner);read.setString(2,id);try(var row=read.executeQuery()){
                                if(!row.next()||row.getBoolean(4)){writer.deleteDocuments(new Term("id",id));continue;}
                                var document=new Document();document.add(new StringField("id",id,Field.Store.YES));document.add(new StringField("owner",owner,Field.Store.NO));
                                document.add(new TextField("title",normalized(row.getString(2)),Field.Store.NO));document.add(new TextField("path",normalized(row.getString(1)),Field.Store.NO));
                                document.add(new Field("body",body(row.getString(3)),BODY));writer.updateDocument(new Term("id",id),document);
                            }
                        }
                    }}
                }
                c.commit();if(generation!=watermark)commit(generation);
            }
        }
        void commit(long generation)throws IOException{writer.setLiveCommitData(Map.of("owner",owner,"generation",Long.toString(generation)).entrySet());writer.commit();watermark=generation;}
        Query words(String text){return across(text,false);}
        Query phrase(String text){return across(text,true);}
        Query across(String text,boolean phrase){
            var queries=new ArrayList<Query>();var builder=new QueryBuilder(analyzer);
            for(var field:List.of("title","path","body")){var query=phrase?builder.createPhraseQuery(field,normalized(text)):builder.createBooleanQuery(field,normalized(text),BooleanClause.Occur.MUST);if(query!=null)queries.add(new BoostQuery(query,field.equals("title")?6:field.equals("path")?3:1));}
            return new BooleanQuery.Builder().add(new TermQuery(new Term("owner",owner)),BooleanClause.Occur.FILTER)
                    .add(new DisjunctionMaxQuery(queries,0.1f),BooleanClause.Occur.MUST).build();
        }
        List<String> ids(Query query)throws IOException{
            try(var reader=DirectoryReader.open(directory)){var searcher=new IndexSearcher(reader);var matches=searcher.search(query,20);var result=new ArrayList<String>();for(var hit:matches.scoreDocs)result.add(searcher.storedFields().document(hit.doc).get("id"));return result;}
        }
        String excerpt(Query query)throws IOException{
            try(var reader=DirectoryReader.open(directory)){
                var searcher=new IndexSearcher(reader);var hits=searcher.search(query,1);if(hits.scoreDocs.length==0)return "";
                int length=searcher.storedFields().document(hits.scoreDocs[0].doc).get("body").length();
                var highlighter=UnifiedHighlighter.builder(searcher,analyzer).withMaxLength(Math.max(1,length)).withHighlightPhrasesStrictly(true).build();
                return highlighter.highlight("body",query,hits,1)[0];
            }
        }
        public void close()throws IOException{writer.close();directory.close();analyzer.close();}
    }
}
