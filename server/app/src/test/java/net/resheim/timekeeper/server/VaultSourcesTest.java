package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultSourcesTest.Profile.class)
class VaultSourcesTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("sources",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_sources_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void aJournalBindingSharesExactCanonicalBytesAndVersionsWithoutCouplingTheLabel(){
        store.transaction("a",()->{
            var date=DayNotes.date("2026-10-30");var notes=new DayNotes(store);var labels=new DayLabels(store);
            labels.save(date,etag(labels.get(date)),"Independent label");
            byte[] original="---\r\nunknown: [true, 'Keep this']\r\n---\r\n# Exact body  \r\n[[Other note]]\r\n".getBytes(StandardCharsets.UTF_8);
            notes.saveDocument(date,etag(notes.getContent(date)),original);
            var ref=new VaultSources.Ref(DayNotes.TYPE,DayNotes.id(date),date.toString());var source=new VaultSources(store,limits,ref);
            assertEquals(etag(notes.getContent(date)),source.snapshot().tag());assertArrayEquals(original,source.read());
            labels.save(date,etag(labels.get(date)),"Renamed independent label");assertEquals(etag(notes.getContent(date)),source.snapshot().tag());
            byte[] next="# Vault edit  \r\n".getBytes(StandardCharsets.UTF_8);source.write(source.snapshot().tag(),next,true);
            assertEquals("# Vault edit  \r\n",notes.getContent(date).path("body").asText());assertArrayEquals(next,notes.document(date));
            source.delete(source.snapshot().tag());assertTrue(notes.getContent(date).path("deleted").asBoolean());
            assertEquals("Renamed independent label",labels.get(date).path("text").asText());return null;
        });
    }
    @Test void weeklyAndEventNotesUseTheirExistingCanonicalResources(){
        store.transaction("a",()->{
            var week=WeekNotes.week("2026-W44");var weekly=new WeekNotes(store);weekly.save(week,etag(weekly.get(week)),object().put("body","Weekly"));
            var weekSource=new VaultSources(store,limits,new VaultSources.Ref(WeekNotes.TYPE,uuid(weekly.get(week).path("id")),week.name()));
            assertEquals(etag(weekly.get(week)),weekSource.snapshot().tag());weekSource.write(weekSource.snapshot().tag(),"Updated week".getBytes(StandardCharsets.UTF_8),true);
            assertEquals("Updated week",weekly.get(week).path("body").asText());
            var key=new EventNotes.Key(UUID.randomUUID(),"ab".repeat(32));var event=new EventNotes(store);
            var value=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","Event body");value.set("event",object().put("uid","event uid").put("recurrenceId","").put("title","Meeting"));
            event.saveDocument(key,etag(event.get(key)),EventNotes.document(value));
            var eventSource=new VaultSources(store,limits,new VaultSources.Ref(EventNotes.TYPE,key.id(),key.filename()));
            assertEquals(etag(event.get(key)),eventSource.snapshot().tag());assertArrayEquals(event.document(key),eventSource.read());
            eventSource.write(eventSource.snapshot().tag(),EventNotes.document(value.deepCopy().put("body","Updated event")),true);
            assertEquals("Updated event",event.get(key).path("body").asText());return null;
        });
    }
    @Test void indexBackfillCanStreamBodiesWhileAddingMetadataInTheSameTransaction(){
        store.transaction("stream",()->{
            var index=new VaultIndex(store);index.root();index.create(new VaultPath("journal"),VaultIndex.Kind.FOLDER,null,null,null);
            for(int day=1;day<=40;day++){var date=java.time.LocalDate.of(2026,9,1).plusDays(day-1);var notes=new DayNotes(store);notes.saveContent(date,etag(notes.getContent(date)),object().put("body","Body "+day));}
            store.scan(DayNotes.TYPE,value->{var ref=VaultSources.fromStored(DayNotes.TYPE,value);index.create(new VaultPath("journal/"+ref.key()+".md"),VaultSources.kind(ref),ref.type(),ref.id(),ref.key());});
            assertEquals(40,index.children(uuid(index.at(new VaultPath("journal"),false).path("id"))).size());return null;
        });
    }
    @Test void eventFrontmatterCommentsUnknownMetadataAndLineEndingsSurviveEveryInterface(){
        store.transaction("event-exact",()->{
            var key=new EventNotes.Key(UUID.randomUUID(),"cd".repeat(32));var events=new EventNotes(store);var value=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","");
            value.set("event",object().put("uid","private uid").put("recurrenceId","2026-10-30T10:00:00Z").put("title","Meeting"));
            String header="\ufeff"+new String(EventNotes.document(value),StandardCharsets.UTF_8).replace("\n","\r\n");int split=header.indexOf('\n')+1;
            header=header.substring(0,split)+"# Keep this comment\r\ncustom: {unicode: Ø, flags: [true, 1]}\r\n"+header.substring(split);
            String body="# Body  \r\n<https://example.invalid/link>\r\n---\r\n";byte[] original=(header+body).getBytes(StandardCharsets.UTF_8);
            new VaultCatalog(store,limits).initialize();events.saveDocument(key,etag(events.get(key)),original);
            assertArrayEquals(original,events.document(key));assertFalse(events.get(key).has("markdown"));
            var source=new VaultSources(store,limits,new VaultSources.Ref(EventNotes.TYPE,key.id(),key.filename()));assertArrayEquals(original,source.read());String before=source.snapshot().tag();
            source.write(before,original,true);assertEquals(before,source.snapshot().tag());
            String changedHeader=header.replace("# Keep this comment","# Keep this changed comment");source.write(before,(changedHeader+body).getBytes(StandardCharsets.UTF_8),true);assertNotEquals(before,source.snapshot().tag());
            events.save(key,etag(events.get(key)),object().put("body","Changed through the event editor"));
            byte[] edited=(changedHeader+"Changed through the event editor").getBytes(StandardCharsets.UTF_8);assertArrayEquals(edited,source.read());
            var index=new VaultIndex(store);var entry=index.referenced(EventNotes.TYPE,key.id());var id=uuid(entry.path("id"));source.delete(source.snapshot().tag());assertArrayEquals(edited,new VaultRecycle(store).read(id,source.snapshot().version()));
            var vault=new VaultCollection(store,limits);var deleted=vault.get(id);vault.restore(deleted,vault.tag(deleted));assertArrayEquals(edited,events.document(key));return null;
        });
    }
}
