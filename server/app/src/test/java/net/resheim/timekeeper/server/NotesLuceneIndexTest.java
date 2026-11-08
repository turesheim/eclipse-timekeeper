package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotesLuceneIndexTest {
    @TempDir Path temporary;
    private final NotesLuceneIndex.Identity identity=new NotesLuceneIndex.Identity("alice",UUID.randomUUID(),UUID.randomUUID(),"restore-one");

    private static NotesLuceneIndex.Source source(int number,String path,String markdown){
        var text=NotesSearchText.extract(markdown.getBytes(StandardCharsets.UTF_8));UUID id=new UUID(0,number);
        return new NotesLuceneIndex.Source(id,path,"MARKDOWN","VAULT_FILE",id,1,"source-tag","source-hash",markdown.getBytes(StandardCharsets.UTF_8).length,text.title(),text);
    }
    private static void ready(NotesLuceneIndex index,long generation)throws IOException{index.commit(new NotesLuceneIndex.Watermark(generation,true,null));}
    private static NotesLuceneIndex.Page search(NotesLuceneIndex index,String query,long generation,int limit,NotesLuceneIndex.Position position)throws IOException{
        return index.search(NotesSearchQuery.parse(query),generation,limit,position,false,null,5000);
    }
    @Test void onlyCommittedReadyGenerationsAreVisibleAndCloseDiscardsPendingWrites()throws Exception{
        Path path=temporary.resolve("commit");
        try(var index=new NotesLuceneIndex(path,identity)){
            index.put(source(1,"Notes/One.md","needle original"));
            assertThrows(NotesLuceneIndex.Unavailable.class,()->search(index,"needle",0,50,null));
            ready(index,1);assertEquals(1,search(index,"needle",1,50,null).items().size());
            index.put(source(1,"Notes/One.md","replacement"));index.put(source(2,"Notes/Two.md","needle pending"));
            assertEquals("needle original",search(index,"needle",1,50,null).items().getFirst().excerpt().text());
            assertThrows(NotesLuceneIndex.Unavailable.class,()->search(index,"needle",2,50,null));
        }
        try(var index=new NotesLuceneIndex(path,identity)){
            assertEquals(new NotesLuceneIndex.Watermark(1,true,null),index.watermark());
            assertEquals(List.of(new UUID(0,1)),search(index,"needle",1,50,null).items().stream().map(NotesLuceneIndex.Hit::id).toList());
            index.remove(new UUID(0,1));ready(index,2);assertTrue(search(index,"needle",2,50,null).items().isEmpty());
        }
    }
    @Test void paginationContinuesAcrossReplicasWithDifferentSegmentAndDeletionHistories()throws Exception{
        try(var a=new NotesLuceneIndex(temporary.resolve("a"),identity);var b=new NotesLuceneIndex(temporary.resolve("b"),identity)){
            for(int i=1;i<=231;i++)a.put(source(i,"Notes/Entry-%03d.md".formatted(i),"obsolete needle"));
            ready(a,1);
            for(int i=231;i>=1;i--){
                var value=source(i,"Notes/Entry-%03d.md".formatted(i),(i%10==0?"# Needle title\n\n":"")+"body needle");
                a.put(value);b.put(value);
                if(i%60==0)a.commit(new NotesLuceneIndex.Watermark(1,false,null));
            }
            ready(a,2);ready(b,2);
            var actual=new ArrayList<UUID>();NotesLuceneIndex.Position position=null;int page=0;
            do{var result=search(page++%2==0?a:b,"needle",2,50,position);actual.addAll(result.items().stream().map(NotesLuceneIndex.Hit::id).toList());position=result.next();}while(position!=null);
            var expected=new ArrayList<UUID>();for(int i=10;i<=230;i+=10)expected.add(new UUID(0,i));for(int i=1;i<=231;i++)if(i%10!=0)expected.add(new UUID(0,i));
            assertEquals(expected,actual);assertEquals(231,new HashSet<>(actual).size());
        }
    }
    @Test void phraseOffsetsRespectBlocksAndExcerptsArePlainTextWithUtf16Ranges()throws Exception{
        try(var index=new NotesLuceneIndex(temporary.resolve("offsets"),identity)){
            index.put(source(1,"Notes/Single.md","Intro 🚀 prose.\n\n~~~html\n<script>needle marker</script>\n~~~\n\ncache\n\nmisses"));
            ready(index,1);var hit=search(index,"needle",1,50,null).items().getFirst();
            assertTrue(hit.excerpt().text().contains("<script>needle marker</script>"));
            assertEquals(List.of("needle"),hit.excerpt().highlights().stream().map(r->hit.excerpt().text().substring(r.start(),r.end())).toList());
            assertTrue(search(index,"\"cache misses\"",1,50,null).items().isEmpty());
            assertFalse(search(index,"cache misses",1,50,null).items().isEmpty());
            assertFalse(hit.toString().contains("script"));
        }
    }
    @Test void maximumDefaultMarkdownAndChunkBoundarySurrogatesHaveBoundedMatchingExcerpts()throws Exception{
        int maximum=10_000_000;
        String tail=" 🚀 uniquetailtoken",prefix="word ".repeat((maximum-tail.getBytes(StandardCharsets.UTF_8).length)/5);
        String markdown=prefix+" ".repeat(maximum-prefix.length()-tail.getBytes(StandardCharsets.UTF_8).length)+tail;
        assertEquals(maximum,markdown.getBytes(StandardCharsets.UTF_8).length);
        String boundary="x ".repeat((NotesLuceneIndex.CHUNK-2)/2)+" 🚀 boundarytoken followed by words";
        try(var index=new NotesLuceneIndex(temporary.resolve("large"),identity)){
            index.put(source(1,"Notes/Large.md",markdown));index.put(source(2,"Notes/Boundary.md",boundary));ready(index,1);
            for(String query:List.of("uniquetailtoken","boundarytoken")){
                var excerpt=search(index,query,1,50,null).items().getFirst().excerpt();
                assertTrue(excerpt.text().length()<=NotesLuceneIndex.EXCERPT);assertTrue(excerpt.text().contains("🚀"));
                assertEquals(List.of(query),excerpt.highlights().stream().map(r->excerpt.text().substring(r.start(),r.end())).toList());
                for(int i=0;i<excerpt.text().length();i++)if(Character.isHighSurrogate(excerpt.text().charAt(i)))assertTrue(i+1<excerpt.text().length()&&Character.isLowSurrogate(excerpt.text().charAt(++i)));
            }
        }
    }
    @Test void configurationAndHiddenJournalAreFilteredBeforePagination()throws Exception{
        try(var index=new NotesLuceneIndex(temporary.resolve("scope"),identity)){
            index.put(source(1,".obsidian/Settings.md","# Needle"));index.put(source(2,"journal/2026-11-08.md","# Needle"));index.put(source(3,"Notes/Visible.md","needle"));ready(index,1);
            var result=index.search(NotesSearchQuery.parse("needle"),1,1,null,false,"journal",5000);
            assertEquals(new UUID(0,3),result.items().getFirst().id());assertNull(result.next());
            assertEquals(3,index.search(NotesSearchQuery.parse("needle"),1,50,null,true,null,5000).items().size());
        }
    }
    @Test void ownerCollectionEpochAndRestoreGenerationFencePersistentCaches()throws Exception{
        Path path=temporary.resolve("identity");
        try(var index=new NotesLuceneIndex(path,identity)){index.put(source(1,"Notes/One.md","private needle"));ready(index,1);}
        for(var different:List.of(new NotesLuceneIndex.Identity("bob",identity.collection(),identity.epoch(),identity.restoreGeneration()),new NotesLuceneIndex.Identity("alice",UUID.randomUUID(),identity.epoch(),identity.restoreGeneration()),new NotesLuceneIndex.Identity("alice",identity.collection(),UUID.randomUUID(),identity.restoreGeneration()),new NotesLuceneIndex.Identity("alice",identity.collection(),identity.epoch(),"restore-two")))
            assertThrows(NotesLuceneIndex.RebuildRequired.class,()->new NotesLuceneIndex(path,different));
        try(var index=new NotesLuceneIndex(path,identity)){assertEquals(1,search(index,"needle",1,50,null).items().size());}
        if(Files.getFileStore(path).supportsFileAttributeView("posix")){
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(path));
            try(var files=Files.list(path)){for(var file:files.toList())assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));}
        }
    }
}
