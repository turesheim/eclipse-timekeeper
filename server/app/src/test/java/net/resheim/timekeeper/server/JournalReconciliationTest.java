package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(JournalImportTest.Profile.class)
class JournalReconciliationTest {
    @Inject ServerStore store;
    private UUID device,other,client;
    private final Map<String,String> bases=new HashMap<>();
    private static final String NAME="2026-10-25.md",CONFIG="f".repeat(64);
    private static final String PASSWORD_HASH=AccountPasswords.hash("Independent bidirectional journal password");
    @BeforeEach void setup()throws Exception{
        io.quarkus.arc.ClientProxy.unwrap(store).stopMaintenance();bases.clear();client=UUID.randomUUID();
        for(String owner:List.of("a","b"))store.transaction(owner,()->{
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND (entity_type LIKE 'JOURNAL_%' OR entity_type='DAY_NOTE')",owner);
            store.update("UPDATE tk_account SET state='ENABLED',username=?,password_hash=? WHERE owner_id=?","reconciliation-"+owner,PASSWORD_HASH,owner);
            new JournalDevices(store).configure(true,true);return null;
        });
        String first=JournalKeyTest.publicKey(),second=JournalKeyTest.publicKey();
        device=tx(()->uuid(new JournalDevices(store).enroll("Bidirectional",first,null,true).get("id")));
        other=tx(()->uuid(new JournalDevices(store).enroll("Other device",second,null,true).get("id")));
    }
    private <T>T tx(Supplier<T> work){return store.transaction("a",work);}
    private JournalReconciliation service(){return new JournalReconciliation(store);}
    private byte[] bytes(String value){return value==null?null:value.getBytes(StandardCharsets.UTF_8);}
    private ObjectNode offer(long sequence,String source){return offer(UUID.randomUUID(),sequence,NAME,source);}
    private ObjectNode offer(UUID exchange,long sequence,String name,String source){return tx(()->service().offer(exchange,device,client,sequence,name,bases.get(name),source!=null,bytes(source),CONFIG));}
    private ObjectNode acknowledge(ObjectNode offer){
        var receipt=tx(()->service().acknowledge(uuid(offer.get("id")),device,offer.path("token").asText(),offer.path("targetExists").asBoolean(),offer.path("targetHash").asText(),CONFIG));
        bases.put(offer.path("filename").asText(),receipt.path("token").asText());return receipt;
    }
    private String outbound(ObjectNode offer){return new String(tx(()->service().outbound(uuid(offer.get("id")),device,CONFIG)),StandardCharsets.UTF_8);}
    private ObjectNode note(){return tx(()->new DayNotes(store).get(JournalMarkdown.date(NAME)));}
    private void calendar(String value){tx(()->{var notes=new DayNotes(store);return notes.save(JournalMarkdown.date(NAME),etag(notes.get(JournalMarkdown.date(NAME))),object().put("body",value));});}
    private ObjectNode resolve(ObjectNode offer,boolean incoming){return tx(()->service().resolve(uuid(offer.get("id")),offer.path("candidateEtag").asText(),etag(new DayNotes(store).get(JournalMarkdown.date(NAME))),incoming,device,CONFIG));}
    @Test void newExternalNoteHasNoCommonBaseUntilAcknowledgementAndRetriesNeverRepeatWrites(){
        UUID exchange=UUID.randomUUID();String source="---\r\ntimekeeper-label: Planning\r\nunknown: preserved\r\n---\r\n\r\n# Exact  \r\n";
        var offer=offer(exchange,1,NAME,source);assertEquals("OFFERED",offer.path("state").asText());assertEquals(source,outbound(offer));assertEquals(1,note().path("version").asLong());
        assertTrue(tx(()->new JournalState(store).list(JOURNAL_COMMON).isEmpty()));
        assertEquals(offer.toString(),offer(exchange,1,NAME,source).toString());assertEquals(1,note().path("version").asLong());
        var receipt=acknowledge(offer);assertEquals("ACKNOWLEDGED",receipt.path("state").asText());
        assertEquals(1,tx(()->new JournalState(store).list(JOURNAL_COMMON).size()));
        calendar("Later Calendar edit");
        assertEquals(receipt.toString(),tx(()->service().acknowledge(uuid(offer.get("id")),device,offer.path("token").asText(),true,offer.path("targetHash").asText(),CONFIG)).toString());
        assertEquals("Later Calendar edit",note().path("body").asText());
        var next=offer(2,source);assertEquals("Later Calendar edit",JournalMarkdown.parse(NAME,bytes(outbound(next))).body());assertEquals(2,note().path("version").asLong());
    }
    @Test void databaseOnlyCreationAndExternalOnlyEditsRoundTripUnknownPropertiesWithoutDuplicateChanges(){
        calendar("# Calendar creation\r\n");var initial=offer(1,null);assertTrue(initial.path("targetExists").asBoolean());assertFalse(initial.path("expectedExists").asBoolean());String first=outbound(initial);acknowledge(initial);
        String external="---\ntimekeeper-label: External\ncustom: [1, true, null]\n---\n# Edited locally  \r\n";
        var changed=offer(2,external);assertEquals("OFFERED",changed.path("state").asText());acknowledge(changed);assertEquals("External",note().path("text").asText());assertEquals(2,note().path("version").asLong());
        long revision=tx(store::revision);var same=offer(3,external);acknowledge(same);assertEquals(revision,tx(store::revision));assertEquals(2,note().path("version").asLong());
        calendar("<unsupported>\r\n\t  ");var exported=offer(4,external);var document=JournalMarkdown.document(NAME,bytes(outbound(exported)));
        assertEquals(Arrays.asList(1,true,null),document.metadata().get("custom"));assertEquals("<unsupported>\r\n\t  ",document.note().body());assertEquals("External",document.note().label());
        assertEquals("# Calendar creation\r\n",JournalMarkdown.parse(NAME,bytes(first)).body());
    }
    @Test void simultaneousEditsPreserveBothSnapshotsAndRequireAFreshExplicitDecision(){
        acknowledge(offer(1,"Base"));calendar("PRIVATE_SERVER_VARIANT");var conflict=offer(2,"PRIVATE_LOCAL_VARIANT");assertEquals("CONFLICT",conflict.path("state").asText());
        var candidate=tx(()->service().candidate(uuid(conflict.get("id"))));assertEquals("PRIVATE_LOCAL_VARIANT",candidate.path("incoming").path("body").asText());assertEquals("PRIVATE_SERVER_VARIANT",candidate.path("remote").path("body").asText());
        assertFalse(conflict.toString().contains("PRIVATE_"));assertThrows(ApiFailure.class,()->outbound(conflict));assertThrows(ApiFailure.class,()->acknowledge(conflict));
        String stale=etag(note());calendar("PRIVATE_SERVER_NEWER_VARIANT");
        assertEquals(412,assertThrows(ApiFailure.class,()->tx(()->service().resolve(uuid(conflict.get("id")),conflict.path("candidateEtag").asText(),stale,true,device,CONFIG))).status);
        assertEquals("PRIVATE_SERVER_NEWER_VARIANT",note().path("body").asText());var resolved=resolve(conflict,true);assertEquals("PRIVATE_LOCAL_VARIANT",note().path("body").asText());
        assertEquals("PRIVATE_LOCAL_VARIANT",outbound(resolved));acknowledge(resolved);
        assertEquals("OFFERED",offer(3,"PRIVATE_LOCAL_VARIANT").path("state").asText());
    }
    @Test void clearDeleteAndRecreationAreDifferentAndOldDeviceContentCannotResurrectATombstone(){
        acknowledge(offer(1,""));assertEquals(1,note().path("version").asLong());assertFalse(note().path("deleted").asBoolean());
        tx(()->new DayNotes(store).delete(JournalMarkdown.date(NAME),etag(new DayNotes(store).get(JournalMarkdown.date(NAME)))));
        var removed=offer(2,"");assertFalse(removed.path("targetExists").asBoolean());assertTrue(removed.path("remoteDeleted").asBoolean());acknowledge(removed);
        var recreated=offer(3,"");assertTrue(recreated.path("targetExists").asBoolean());assertFalse(note().path("deleted").asBoolean());assertEquals(3,note().path("version").asLong());acknowledge(recreated);
        var localDelete=offer(4,null);assertFalse(localDelete.path("targetExists").asBoolean());assertTrue(note().path("deleted").asBoolean());acknowledge(localDelete);long version=note().path("version").asLong();
        acknowledge(offer(5,null));assertEquals(version,note().path("version").asLong());
        var stale=tx(()->service().offer(UUID.randomUUID(),other,UUID.randomUUID(),1,NAME,null,true,bytes("Stale other device"),CONFIG));assertEquals("CONFLICT",stale.path("state").asText());assertTrue(note().path("deleted").asBoolean());
    }
    @Test void deleteEditConflictsCanKeepCalendarOrDeliberatelyRestoreAnIncomingFile(){
        acknowledge(offer(1,"Base"));calendar("Calendar peer edit");var deleted=offer(2,null);assertEquals("CONFLICT",deleted.path("state").asText());
        var candidate=tx(()->service().candidate(uuid(deleted.get("id"))));assertFalse(candidate.path("incoming").path("exists").asBoolean());
        var keep=resolve(deleted,false);assertEquals("Calendar peer edit",outbound(keep));acknowledge(keep);
        tx(()->new DayNotes(store).delete(JournalMarkdown.date(NAME),etag(new DayNotes(store).get(JournalMarkdown.date(NAME)))));
        var edited=offer(3,"Local peer edit");assertEquals("CONFLICT",edited.path("state").asText());assertTrue(edited.path("remoteDeleted").asBoolean());
        var restore=resolve(edited,true);assertEquals("Local peer edit",outbound(restore));assertFalse(note().path("deleted").asBoolean());acknowledge(restore);
    }
    @Test void acknowledgementsUseTheirSnapshotEvenWhenABrowserSavesBeforeTheyArrive(){
        var first=offer(1,"Initial");calendar("Saved while client applied export");acknowledge(first);
        var next=offer(2,"Initial");assertEquals("OFFERED",next.path("state").asText());assertEquals("Saved while client applied export",outbound(next));
        assertEquals(2,note().path("version").asLong());
    }
    @Test void currentCommonReceiptsSurviveExchangeHistoryRetention(){
        var offered=offer(1,"Retained common state");var receipt=acknowledge(offered);UUID id=uuid(offered.get("id"));
        tx(()->{var state=new JournalState(store);state.remove(JOURNAL_OFFER,state.required(JOURNAL_OFFER,id));return null;});
        var restored=tx(()->new JournalReconciliation(store).acknowledge(id,device,offered.path("token").asText(),true,offered.path("targetHash").asText(),CONFIG));
        assertEquals(receipt.toString(),restored.toString());assertEquals(1,note().path("version").asLong());
        assertThrows(ApiFailure.class,()->tx(()->service().acknowledge(id,device,offered.path("token").asText(),true,"0".repeat(64),CONFIG)));
    }
    @Test void staleOffersWrongReceiptsAndLostClientStateCannotReplaceTheCommonBase(){
        var first=offer(1,"Base");acknowledge(first);calendar("Server change");var older=offer(2,"Base");var newer=offer(3,"Base");
        assertEquals("JOURNAL_SUPERSEDED",assertThrows(ApiFailure.class,()->acknowledge(older)).code);
        assertThrows(ApiFailure.class,()->tx(()->service().acknowledge(uuid(newer.get("id")),device,newer.path("token").asText(),true,"0".repeat(64),CONFIG)));
        assertThrows(ApiFailure.class,()->tx(()->service().acknowledge(uuid(newer.get("id")),device,UUID.randomUUID().toString(),true,newer.path("targetHash").asText(),CONFIG)));
        acknowledge(newer);String token=bases.get(NAME);
        tx(()->service().acknowledge(uuid(first.get("id")),device,first.path("token").asText(),true,first.path("targetHash").asText(),CONFIG));
        assertEquals(token,tx(()->new JournalState(store).list(JOURNAL_COMMON).getFirst().path("token").asText()));
        assertEquals("JOURNAL_CLIENT_STATE_CHANGED",assertThrows(ApiFailure.class,()->tx(()->service().offer(UUID.randomUUID(),device,UUID.randomUUID(),4,NAME,token,false,null,CONFIG))).code);
        assertEquals("JOURNAL_BASE_CHANGED",assertThrows(ApiFailure.class,()->tx(()->service().offer(UUID.randomUUID(),device,client,4,NAME,null,false,null,CONFIG))).code);
        assertEquals("Server change",note().path("body").asText());
    }
    @Test void writeOnlyForeignRevokedAndDisabledCredentialsCannotReadPreparedExchanges()throws Exception{
        var offered=offer(1,"Private outbound note");String publicKey=JournalKeyTest.publicKey();UUID writeOnly=tx(()->uuid(new JournalDevices(store).enroll("Write only",publicKey,null).get("id")));
        assertEquals("JOURNAL_DEVICE_WRITE_ONLY",assertThrows(ApiFailure.class,()->tx(()->service().outbound(uuid(offered.get("id")),writeOnly,CONFIG))).code);
        assertEquals(404,assertThrows(ApiFailure.class,()->tx(()->service().outbound(uuid(offered.get("id")),other,CONFIG))).status);
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("b",()->service().candidate(uuid(offered.get("id"))))).status);
        assertEquals("JOURNAL_AUTHORIZATION_CHANGED",assertThrows(ApiFailure.class,()->tx(()->service().outbound(uuid(offered.get("id")),device,"e".repeat(64)))).code);
        tx(()->new JournalDevices(store).configure(true,false));assertEquals("JOURNAL_BIDIRECTIONAL_DISABLED",assertThrows(ApiFailure.class,()->outbound(offered)).code);
        tx(()->new JournalDevices(store).configure(true,true));assertEquals("JOURNAL_AUTHORIZATION_CHANGED",assertThrows(ApiFailure.class,()->outbound(offered)).code);
        var fresh=offer(2,"Private outbound note");tx(()->new JournalDevices(store).revoke(device));assertEquals("JOURNAL_DEVICE_REVOKED",assertThrows(ApiFailure.class,()->outbound(fresh)).code);
        assertThrows(ApiFailure.class,()->acknowledge(fresh));
    }
    @Test void metadataOnlyChangesPropagateAcrossDevicesWithoutChangingCalendarVersions(){
        String initial="---\ncustom: one\n---\nBody";acknowledge(offer(1,initial));UUID secondClient=UUID.randomUUID();
        var otherInitial=tx(()->service().offer(UUID.randomUUID(),other,secondClient,1,NAME,null,false,null,CONFIG));
        tx(()->service().acknowledge(uuid(otherInitial.get("id")),other,otherInitial.path("token").asText(),true,otherInitial.path("targetHash").asText(),CONFIG));
        long version=note().path("version").asLong(),revision=tx(store::revision);String changed="---\ncustom: two\n---\nBody";acknowledge(offer(2,changed));
        assertEquals(version,note().path("version").asLong());assertEquals(revision,tx(store::revision));
        var outgoing=tx(()->service().offer(UUID.randomUUID(),other,secondClient,2,NAME,otherInitial.path("token").asText(),true,bytes(initial),CONFIG));
        assertEquals("OFFERED",outgoing.path("state").asText());byte[] output=tx(()->service().outbound(uuid(outgoing.get("id")),other,CONFIG));assertEquals("two",JournalMarkdown.document(NAME,output).metadata().get("custom"));
    }
    @Test void transactionFailurePreservesBothTheNoteAndUnacknowledgedWork(){
        UUID exchange=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->tx(()->{service().offer(exchange,device,client,1,NAME,null,true,bytes("Must roll back"),CONFIG);throw new IllegalStateException("before commit");}));
        assertEquals(0,note().path("version").asLong());assertTrue(tx(()->new JournalState(store).list(JOURNAL_OFFER).isEmpty()));
        var retried=offer(exchange,1,NAME,"Must roll back");assertEquals("Must roll back",outbound(retried));acknowledge(retried);
    }
    @Test void aNewerCompletedFileErrorPreventsAnOlderPendingSnapshotFromReplaying(){
        tx(()->new JournalState(store).insert(JOURNAL_ITEM,UUID.randomUUID(),object().put("deviceId",device.toString()).put("filename",NAME).put("sequence",2).put("state","ERROR").put("error","INVALID_YAML")));
        assertEquals("JOURNAL_SUPERSEDED",assertThrows(ApiFailure.class,()->offer(1,"Stale pending bytes")).code);
        assertEquals(0,note().path("version").asLong());assertTrue(tx(()->new JournalState(store).list(JOURNAL_OFFER).isEmpty()));
        acknowledge(offer(3,"Corrected current bytes"));assertEquals("Corrected current bytes",note().path("body").asText());
    }
}
