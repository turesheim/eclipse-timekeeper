package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;

class NotesSearchQueryTest {
    private static Document document(String id,String owner,String title,String path,String kind,boolean deleted,String... blocks){
        var doc=new Document();doc.add(new StringField("id",id,Field.Store.YES));doc.add(new StringField("owner",owner,Field.Store.NO));
        doc.add(new StringField("kind",kind,Field.Store.NO));doc.add(new StringField("deleted",Boolean.toString(deleted),Field.Store.NO));
        doc.add(new StringField("configuration",Boolean.toString(VaultSettings.configurationPath(new VaultPath(path))),Field.Store.NO));
        doc.add(new StringField("path_key",VaultPath.searchKey(path),Field.Store.NO));
        doc.add(new TextField("title",NotesSearchText.normalized(title),Field.Store.NO));doc.add(new TextField("path",NotesSearchText.normalized(path),Field.Store.NO));
        for(String block:blocks)doc.add(new TextField("body",NotesSearchText.normalized(block),Field.Store.NO));return doc;
    }
    private static Map<String,Float> matches(IndexWriter writer,String query,String owner,boolean configuration,String hidden)throws Exception{
        try(var reader=DirectoryReader.open(writer)){
            var searcher=new IndexSearcher(reader);var hits=searcher.search(NotesSearchQuery.scoped(NotesSearchQuery.parse(query),owner,configuration,hidden),100);
            var result=new LinkedHashMap<String,Float>();for(var hit:hits.scoreDocs)result.put(searcher.storedFields().document(hit.doc).get("id"),hit.score);return result;
        }
    }
    @Test void allWordsCanMatchAcrossFieldsAndPhrasesCannotCrossBlocks()throws Exception{
        try(var directory=new ByteBuffersDirectory();var analyzer=NotesSearchQuery.analyzer();var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))){
            writer.addDocument(document("split","alice","Alpha","Notes/Beta.md","MARKDOWN",false,"Gamma","cache","misses"));
            writer.addDocument(document("phrase","alice","","Notes/Phrase.md","MARKDOWN",false,"Cache misses during a rolling restart."));
            assertEquals(Set.of("split"),matches(writer,"alpha beta gamma","alice",false,null).keySet());
            assertEquals(Set.of("phrase"),matches(writer,"\"cache misses\"","alice",false,null).keySet());
            assertEquals(Set.of("split","phrase"),matches(writer,"cache misses","alice",false,null).keySet());
        }
    }
    @Test void unicodeTechnicalWordsAndPunctuationStayLiteralAndBounded()throws Exception{
        try(var directory=new ByteBuffersDirectory();var analyzer=NotesSearchQuery.analyzer();var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))){
            writer.addDocument(document("literal","alice","","Notes/Plain.md","MARKDOWN",false,"Feilsøking café foo_bar service.cluster.local title needle title:needle"));
            writer.addDocument(document("field","alice","needle","Notes/Field.md","MARKDOWN",false,"other words"));
            writer.addDocument(document("prefix","alice","","Notes/Prefix.md","MARKDOWN",false,"needles"));
            assertEquals(Set.of("literal"),matches(writer,"FEILSØKING Cafe\u0301 foo_bar service.cluster.local","alice",false,null).keySet());
            assertEquals(Set.of("literal"),matches(writer,"title:needle","alice",false,null).keySet());
            assertEquals(Set.of("literal","field"),matches(writer,"needle*","alice",false,null).keySet());
            assertTrue(matches(writer,"* /[a-z]+/","alice",false,null).isEmpty());
            assertThrows(ApiFailure.class,()->NotesSearchQuery.parse("\"unfinished"));
            assertThrows(ApiFailure.class,()->NotesSearchQuery.parse("x".repeat(513)));
            assertThrows(ApiFailure.class,()->NotesSearchQuery.parse("word ".repeat(33)));
        }
    }
    @Test void visibilityAndOwnershipAreAppliedBeforeResults()throws Exception{
        try(var directory=new ByteBuffersDirectory();var analyzer=NotesSearchQuery.analyzer();var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))){
            writer.addDocument(document("own","alice","","Notes/Own.md","MARKDOWN",false,"needle"));
            writer.addDocument(document("foreign","bob","","Notes/Foreign.md","MARKDOWN",false,"needle"));
            writer.addDocument(document("trash","alice","","Notes/Trash.md","MARKDOWN",true,"needle"));
            writer.addDocument(document("binary","alice","","Notes/Data.bin","BINARY",false,"needle"));
            writer.addDocument(document("config","alice","",".obsidian/Config.md","MARKDOWN",false,"needle"));
            writer.addDocument(document("day","alice","","journal/2026-11-02.md","DAY",false,"needle"));
            assertEquals(Set.of("own","day"),matches(writer,"needle","alice",false,null).keySet());
            assertEquals(Set.of("own","day","config"),matches(writer,"needle","alice",true,null).keySet());
            assertEquals(Set.of("own"),matches(writer,"needle","alice",false,"journal").keySet());
        }
    }
    @Test void typeQualifiersRestrictWordsAndPhrasesWithoutChangingUnqualifiedSearch()throws Exception{
        try(var directory=new ByteBuffersDirectory();var analyzer=NotesSearchQuery.analyzer();var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))){
            for(String kind:List.of("MARKDOWN","DAY","WEEK","EVENT"))writer.addDocument(document(kind,"alice","Planning","Notes/"+kind+".md",kind,false,"Cache misses during planning."));
            writer.addDocument(document("foreign","bob","Planning","Notes/Foreign.md","EVENT",false,"Cache misses during planning."));
            writer.addDocument(document("trash","alice","Planning","Notes/Trash.md","EVENT",true,"Cache misses during planning."));
            assertEquals(Set.of("MARKDOWN","DAY","WEEK","EVENT"),matches(writer,"planning","alice",false,null).keySet());
            assertEquals(Set.of("EVENT"),matches(writer,"event:planning","alice",false,null).keySet());
            assertEquals(Set.of("DAY","WEEK"),matches(writer,"journal:\"cache misses\" planning","alice",false,null).keySet());
            assertEquals(Set.of("MARKDOWN"),matches(writer,"note: planning","alice",false,null).keySet());
            assertEquals(Set.of("EVENT"),matches(writer,"EVENT:","alice",false,null).keySet());
            assertTrue(matches(writer,"event: journal:","alice",false,null).isEmpty());
        }
    }
    @Test void fileQualifierMatchesLiteralPathSubstringsAndCombinesWithOtherFilters()throws Exception{
        try(var directory=new ByteBuffersDirectory();var analyzer=NotesSearchQuery.analyzer();var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))){
            writer.addDocument(document("path","alice","Other title","Reports/Release planning.md","MARKDOWN",false,"needle"));
            writer.addDocument(document("body","alice","Release planning","Notes/Other.md","MARKDOWN",false,"Reports release planning needle"));
            assertEquals(Set.of("path"),matches(writer,"file:\"release PLAN\" needle note:","alice",false,null).keySet());
            assertEquals(Set.of("path"),matches(writer,"file:Reports","alice",false,null).keySet());
            assertTrue(matches(writer,"file:* needle","alice",false,null).isEmpty());
            assertTrue(matches(writer,"event: file:Reports","alice",false,null).isEmpty());
            assertThrows(ApiFailure.class,()->NotesSearchQuery.parse("file:"));
            assertThrows(ApiFailure.class,()->NotesSearchQuery.parse("journal:\"unfinished"));
        }
    }
    @Test void titlePathBodyWeightsStayIdenticalAfterDifferentIndexHistories()throws Exception{
        try(var a=new ByteBuffersDirectory();var b=new ByteBuffersDirectory();var aa=NotesSearchQuery.analyzer();var ab=NotesSearchQuery.analyzer();var wa=new IndexWriter(a,new IndexWriterConfig(aa));var wb=new IndexWriter(b,new IndexWriterConfig(ab))){
            var title=document("title","alice","Needle","Notes/A.md","MARKDOWN",false,"other");
            var path=document("path","alice","","Notes/Needle.md","MARKDOWN",false,"other");
            var body=document("body","alice","","Notes/C.md","MARKDOWN",false,"needle");
            for(var doc:List.of(title,path,body)){wa.addDocument(doc);wb.addDocument(doc);}
            for(int i=0;i<10;i++){wa.updateDocument(new Term("id","body"),document("body","alice","","Notes/C.md","MARKDOWN",false,"needle ".repeat(i+1)));wa.commit();}
            wa.updateDocument(new Term("id","body"),body);wa.commit();
            assertEquals(matches(wb,"needle","alice",false,null),matches(wa,"needle","alice",false,null));
            assertEquals(List.of("title","path","body"),new ArrayList<>(matches(wa,"needle","alice",false,null).keySet()));
        }
    }
}
