package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultReadPerformanceTest.Profile.class)
class VaultReadPerformanceTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("readperformance",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_read_performance_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @jakarta.enterprise.inject.Vetoed
    private static final class CountedStore extends ServerStore {
        private final ServerStore delegate;private final String type;int copies;
        CountedStore(ServerStore delegate,String type){this.delegate=delegate;this.type=type;}
        @Override public Optional<JsonNode> find(String type,UUID id){if(type.equals(this.type))copies++;return delegate.find(type,id);}
        @Override public String owner(){return delegate.owner();}
    }
    @Test void ordinaryNotesAndLargeImagesReadTheCanonicalPayloadOnce(){
        store.transaction("read-performance-files",()->{
            for(byte[] bytes:List.of(("# A note\n\n"+"Repeated prose. ".repeat(30_000)).getBytes(StandardCharsets.UTF_8),image(4_000_000))){
                var content=new VaultContent(store);UUID id=UUID.randomUUID();content.save(id,etag(content.metadata(id)),bytes,limits.fileBytes());
                var counted=new CountedStore(store,VaultContent.TYPE);var source=new VaultSources(counted,limits,new VaultSources.Ref(VaultContent.TYPE,id,id.toString()));
                assertArrayEquals(bytes,source.read());assertEquals(1,counted.copies,"One content request must copy one canonical SQL payload");
                content.delete(id,etag(content.metadata(id)));counted.copies=0;assertEquals(404,assertThrows(ApiFailure.class,source::read).status);assertEquals(1,counted.copies);
            }
            return null;
        });
    }
    @Test void nativeJournalScreenshotsReadOnePayloadAndRemainOwnerPrivate(){
        byte[] bytes=image(4_000_000);String name="screenshot.png",owner="read-performance-journal";
        store.transaction(owner,()->{
            new JournalAssets(store).save(name,null,bytes);var counted=new CountedStore(store,JournalAssets.TYPE);
            var source=new VaultSources(counted,limits,new VaultSources.Ref(JournalAssets.TYPE,JournalAssets.id(owner,name),name));
            assertArrayEquals(bytes,source.read());assertEquals(1,counted.copies,"A journal screenshot must not be fetched twice");return null;
        });
        store.transaction("read-performance-other-owner",()->{
            var source=new VaultSources(store,limits,new VaultSources.Ref(JournalAssets.TYPE,JournalAssets.id(store.owner(),name),name));assertEquals(404,assertThrows(ApiFailure.class,source::read).status);return null;
        });
    }
    @Test void nativeReadsKeepCanonicalIdentityAndDeletionChecksWithoutAPreflight(){
        store.transaction("read-performance-native",()->{
            var day=new DayNotes(store);var date=DayNotes.date("2026-11-08");day.saveContent(date,etag(day.getContent(date)),object().put("body","private day"));
            var source=new VaultSources(store,limits,new VaultSources.Ref(DayNotes.TYPE,DayNotes.id(date),date.toString()));assertEquals("private day",new String(source.read(),StandardCharsets.UTF_8));
            var invalid=new VaultSources(store,limits,new VaultSources.Ref(DayNotes.TYPE,UUID.randomUUID(),date.toString()));assertThrows(IllegalStateException.class,invalid::read);
            day.deleteContent(date,etag(day.getContent(date)));assertEquals(404,assertThrows(ApiFailure.class,source::read).status);
            var week=WeekNotes.week("2026-W45");var weeks=new WeekNotes(store);weeks.save(week,etag(weeks.get(week)),object().put("body","private week"));
            var weekly=new VaultSources(store,limits,new VaultSources.Ref(WeekNotes.TYPE,WeekNotes.id(week),week.name()));assertEquals("private week",new String(weekly.read(),StandardCharsets.UTF_8));
            weeks.delete(week,etag(weeks.get(week)));assertEquals(404,assertThrows(ApiFailure.class,weekly::read).status);return null;
        });
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="timekeeper.notes.read.benchmark",matches="true")
    void recordSyntheticReadPerformance()throws Exception{
        var report=object().put("bytesPerRead",4_000_000).put("reads",8);String owner="read-benchmark";byte[] original=image(4_000_000);
        store.transaction(owner,()->{
            var content=new VaultContent(store);UUID id=UUID.randomUUID();content.save(id,etag(content.metadata(id)),original,limits.fileBytes());
            new JournalAssets(store).save("benchmark.png",null,original);
            for(var ref:List.of(new VaultSources.Ref(VaultContent.TYPE,id,id.toString()),new VaultSources.Ref(JournalAssets.TYPE,JournalAssets.id(owner,"benchmark.png"),"benchmark.png"))){
                var counted=new CountedStore(store,ref.type());var source=new VaultSources(counted,limits,ref);source.read();counted.copies=0;var times=new ArrayList<Long>();
                for(int i=0;i<8;i++){long start=System.nanoTime();byte[] result=source.read();times.add(System.nanoTime()-start);assertArrayEquals(original,result);}
                Collections.sort(times);report.set(ref.type(),object().put("canonicalPayloadCopies",counted.copies).put("p50Millis",times.get(3)/1_000_000.0).put("p95Millis",times.get(7)/1_000_000.0));
            }
            return null;
        });
        var output=java.nio.file.Path.of("target/vault-read-performance.json");java.nio.file.Files.createDirectories(output.getParent());java.nio.file.Files.writeString(output,report.toPrettyString()+"\n");System.out.println("vaultReadPerformance="+report);
    }
    private static byte[] image(int size){byte[] result=new byte[size];new Random(27).nextBytes(result);byte[] signature={(byte)0x89,'P','N','G',13,10,26,10};System.arraycopy(signature,0,result,0,signature.length);return result;}
}
