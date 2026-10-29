package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.*;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(CalendarHttpTest.Profile.class)
@QuarkusTestResource(value=CalendarHttpTest.Feeds.class,restrictToAnnotatedClass=true)
class CalendarHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            var config=new HashMap<String,String>();String database=System.getenv("TIMEKEEPER_TEST_CALENDAR_DB_URL");
            config.put("quarkus.datasource.jdbc.url",database==null?"jdbc:h2:mem:calendar_http_tests":database);
            if(database!=null){config.put("quarkus.datasource.db-kind","postgresql");config.put("quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"));config.put("quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));}
            return config;
        }
    }
    public static class Feeds implements QuarkusTestResourceLifecycleManager {
        static CalendarDownloadTest.Fixture fixture;
        static final AtomicReference<String> body=new AtomicReference<>();
        static final AtomicInteger status=new AtomicInteger(200),requests=new AtomicInteger();
        static volatile CountDownLatch started,release;
        public Map<String,String> start(){try{
            fixture=new CalendarDownloadTest.Fixture();fixture.on("/calendar.ics",exchange->{
                requests.incrementAndGet();var waiting=release;var arrival=started;if(arrival!=null)arrival.countDown();
                if(waiting!=null)try{waiting.await(8,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                String tag='"'+hash(body.get())+'"';if(status.get()==200&&tag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))){try(exchange){exchange.sendResponseHeaders(304,-1);}return;}
                exchange.getResponseHeaders().add("ETag",tag);CalendarDownloadTest.reply(exchange,status.get(),body.get());
            });return Map.of("timekeeper.calendars.trusted-origins",fixture.origin);
        }catch(Exception failure){throw new IllegalStateException(failure);}}
        public void stop(){if(release!=null)release.countDown();if(fixture!=null)fixture.close();}
    }
    @Inject ServerStore store;
    @Inject MockBackendClock clock;
    @Inject CalendarRefresh refresher;
    private static final Instant NOW=Instant.parse("2026-06-01T12:00:00Z");
    private static String ics(String title){return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timekeeper//HTTP test//EN\r\nBEGIN:VEVENT\r\nUID:http-meeting\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260504T215900Z\r\nDTEND:20260504T220100Z\r\nSUMMARY:"+title+"\r\nDESCRIPTION:Meeting notes\\nSecond line\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";}
    @BeforeEach void reset(){
        clock.set(NOW);Feeds.body.set(ics("Planning"));Feeds.status.set(200);Feeds.requests.set(0);Feeds.started=null;Feeds.release=null;
        for(String owner:List.of("a","b"))store.transaction(owner,()->{
            store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='CAL_SUB'",owner);
            for(String type:List.of(CalendarSubscriptions.SUB,CalendarSubscriptions.FEED,CalendarSubscriptions.LINK,EventNotes.TYPE))store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=?",owner,type);
            store.updateProfile("Europe/Oslo");return null;
        });
    }
    @AfterEach void cleanup(){if(Feeds.release!=null)Feeds.release.countDown();clock.set(null);}
    private Response call(String token,String method,String path,JsonNode body,String match,String key){
        var request=given().header("Authorization","Bearer "+token);if(body!=null)request.contentType("application/json").body(body.toString());if(match!=null)request.header("If-Match",match);if(key!=null)request.header("Idempotency-Key",key);return request.request(method,"/api/v1"+path);
    }
    private Response get(String token,String path){return call(token,"GET",path,null,null,null);}
    private Response post(String token,String path,JsonNode body){return call(token,"POST",path,body,null,UUID.randomUUID().toString());}
    private ObjectNode client(String token){var reply=post(token,"/clients",object().put("name","Calendar client "+UUID.randomUUID()).put("color","#176653"));reply.then().statusCode(201);return (ObjectNode)parse(reply.asString());}
    private ObjectNode configuration(String client){return object().put("name","Planning calendar").put("clientId",client).put("visible",true).put("url",Feeds.fixture.origin+"/calendar.ics?secret=private-access");}
    private Response create(String token,JsonNode input){return post(token,"/calendar-subscriptions",input);}
    private String subscribe(String client){var reply=create("test-a",configuration(client));reply.then().statusCode(201);return "/calendar-subscriptions/"+parse(reply.asString()).path("id").asText();}
    private Response refresh(String path){return post("test-a",path+"/refresh",null);}
    private ObjectNode events(){return (ObjectNode)parse(get("test-a","/calendar-events?fromDate=2026-05-04&toDate=2026-05-11").then().statusCode(200).extract().asString());}
    private ObjectNode activity(JsonNode event){var value=object().putNull("taskId").putNull("projectId").put("summary","Reviewed meeting").put("start",event.path("start").asText()).put("end",event.path("end").asText());value.putArray("labelIds");return value;}
    private ObjectNode conversion(JsonNode event){var value=object().put("fromDate","2026-05-04").put("toDate","2026-05-11").put("eventVersion",event.path("eventVersion").asText());value.set("activity",activity(event));return value;}
    private String conversionPath(String subscription,JsonNode event){return subscription+"/events/"+event.path("key").asText()+"/activity";}
    @Test void configurationHasOwnedConditionalBoundariesAndNeverReturnsTheCredentialUrl(){
        String client=client("test-a").path("id").asText(),foreign=client("test-b").path("id").asText();var input=configuration(client);String key=UUID.randomUUID().toString();
        var created=call("test-a","POST","/calendar-subscriptions",input,null,key);created.then().statusCode(201);assertFalse(created.asString().contains("private-access"));assertFalse(parse(created.asString()).has("url"));
        assertEquals(created.asString(),call("test-a","POST","/calendar-subscriptions",input,null,key).asString());String path="/calendar-subscriptions/"+parse(created.asString()).path("id").asText();
        get("test-read",path).then().statusCode(200);get("test-b",path).then().statusCode(404);assertEquals(0,parse(get("test-b","/calendar-subscriptions").asString()).path("items").size());
        create("test-read",input).then().statusCode(403);create("test-a",configuration(foreign)).then().statusCode(404);create("test-a",input.deepCopy().put("ownerId","b")).then().statusCode(400);
        create("test-a",input).then().statusCode(409);
        var edit=input.deepCopy().put("name","Renamed").put("url","");call("test-a","PUT",path,edit,null,null).then().statusCode(428);
        var changed=call("test-a","PUT",path,edit,created.header("ETag"),null);changed.then().statusCode(200);call("test-a","PUT",path,input,created.header("ETag"),null).then().statusCode(412);
        refresh(path).then().statusCode(200);assertEquals(1,events().path("items").size());
        var clientRecord=get("test-a","/clients/"+client);call("test-a","DELETE","/clients/"+client,null,clientRecord.header("ETag"),null).then().statusCode(409);
        var current=get("test-a",path);call("test-a","DELETE",path,null,current.header("ETag"),null).then().statusCode(204);get("test-a",path).then().statusCode(404);assertEquals(0,events().path("items").size());
    }
    @Test void refreshUsesConditionalsRetainsGoodDataOnFailureAndHonoursVisibility(){
        String path=subscribe(client("test-a").path("id").asText());var first=refresh(path);first.then().statusCode(200);assertNull(parse(first.asString()).get("lastError").textValue());assertNotNull(parse(first.asString()).get("lastSuccess").textValue());
        var event=events().path("items").get(0);assertEquals("Planning",event.path("title").asText());refresh(path).then().statusCode(429);assertEquals(1,Feeds.requests.get());
        clock.set(NOW.plusSeconds(31));var unchanged=refresh(path);unchanged.then().statusCode(200);assertEquals(event.path("eventVersion"),events().path("items").get(0).path("eventVersion"));
        clock.set(NOW.plusSeconds(62));Feeds.status.set(403);var failed=refresh(path);failed.then().statusCode(200);assertEquals("CALENDAR_ACCESS_DENIED",parse(failed.asString()).path("lastError").asText());assertEquals(parse(unchanged.asString()).path("lastSuccess"),parse(failed.asString()).path("lastSuccess"));assertEquals(1,events().path("items").size());assertFalse(failed.asString().contains("private-access"));
        var config=configuration(parse(failed.asString()).path("clientId").asText()).put("url","").put("visible",false);call("test-a","PUT",path,config,failed.header("ETag"),null).then().statusCode(200);assertEquals(0,events().path("items").size());
        clock.set(NOW.plusSeconds(93));Feeds.status.set(200);Feeds.body.set("not an iCalendar document");var invalid=refresh(path);invalid.then().statusCode(200);assertEquals("CALENDAR_INVALID_FEED",parse(invalid.asString()).path("lastError").asText());
    }
    @Test void conversionCommitsDailyPartsAndPermanentIdentityTogetherAndSurvivesRemoval()throws Exception{
        String path=subscribe(client("test-a").path("id").asText());refresh(path).then().statusCode(200);var event=events().path("items").get(0);var input=conversion(event);String key=UUID.randomUUID().toString(),endpoint=conversionPath(path,event);
        int before=store.transaction("a",()->store.list("ACTIVITY").size());var created=call("test-a","POST",endpoint,input,null,key);created.then().statusCode(201);var ids=parse(created.asString()).path("activityIds");assertEquals(2,ids.size());assertEquals(before+2,store.transaction("a",()->store.list("ACTIVITY").size()));
        assertEquals(created.asString(),call("test-a","POST",endpoint,input,null,key).asString());post("test-a",endpoint,input).then().statusCode(409);
        var source=events();assertEquals(ids,source.path("items").get(0).path("activityIds"));for(var id:ids)assertEquals(event.path("uid"),source.path("activitySources").path(id.asText()).path("uid"));
        byte[] exported=get("test-a","/data/account/export").then().statusCode(200).extract().asByteArray();boolean audit=false;
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("archive.json")){
            String json=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertFalse(json.contains("private-access"));var archive=parse(json);for(var item:archive.path("importHistory"))if(item.path("entityType").asText().equals("ARCHIVE_AUDIT")&&item.path("data").path("kind").asText().equals("CALENDAR_ACTIVITY_SOURCE"))audit=true;
        }}assertTrue(audit,"Calendar provenance is portable as an inert audit copy, without feed credentials");
        clock.set(NOW.plusSeconds(31));Feeds.body.set("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nEND:VCALENDAR\r\n");refresh(path).then().statusCode(200);assertEquals(0,events().path("items").size());for(var id:ids)get("test-a","/activities/"+id.asText()).then().statusCode(200);
        var current=get("test-a",path);call("test-a","DELETE",path,null,current.header("ETag"),null).then().statusCode(204);for(var id:ids)get("test-a","/activities/"+id.asText()).then().statusCode(200);
        assertEquals(created.asString(),call("test-a","POST",endpoint,input,null,key).asString());assertEquals(2,events().path("activitySources").size());
        String retained=ids.get(1).asText();var record=get("test-a","/activities/"+retained);var edited=object().put("summary","Edited day fragment").put("start","2026-05-04T21:50:00Z").put("end","2026-05-04T22:01:00Z");edited.putArray("labelIds");call("test-a","PUT","/activities/"+retained,edited,record.header("ETag"),null).then().statusCode(200);assertEquals(3,events().path("activitySources").size(),"New daily fragments inherit the source audit after a subscription is removed");
    }
    @Test void deletedConvertedActivitiesDoNotEnableSilentReconversion(){
        String path=subscribe(client("test-a").path("id").asText());refresh(path).then().statusCode(200);var event=events().path("items").get(0);var input=conversion(event);String endpoint=conversionPath(path,event);
        var saved=post("test-a",endpoint,input).then().statusCode(201).extract().asString();for(var id:parse(saved).path("activityIds")){var record=get("test-a","/activities/"+id.asText());call("test-a","DELETE","/activities/"+id.asText(),null,record.header("ETag"),null).then().statusCode(204);}
        post("test-a",endpoint,input).then().statusCode(409);assertEquals(2,events().path("items").get(0).path("activityIds").size());
    }
    @Test void staleReadOnlyForeignFutureAndMismatchedClientConversionsCannotCreateActivities(){
        String client=client("test-a").path("id").asText(),path=subscribe(client);refresh(path).then().statusCode(200);var event=events().path("items").get(0);var input=conversion(event);String endpoint=conversionPath(path,event);int before=store.transaction("a",()->store.list("ACTIVITY").size());
        post("test-read",endpoint,input).then().statusCode(403);post("test-b",endpoint,input).then().statusCode(404);
        var future=input.deepCopy();((ObjectNode)future.get("activity")).put("start","2026-06-02T09:00:00Z").put("end","2026-06-02T10:00:00Z");post("test-a",endpoint,future).then().statusCode(422);
        var open=input.deepCopy();((ObjectNode)open.get("activity")).putNull("end");post("test-a",endpoint,open).then().statusCode(422);
        String other=client("test-a").path("id").asText();var project=post("test-a","/projects",object().put("name","Other client project").put("clientId",other)).then().statusCode(201).extract().asString();var wrong=input.deepCopy();((ObjectNode)wrong.get("activity")).put("projectId",parse(project).path("id").asText());post("test-a",endpoint,wrong).then().statusCode(422);
        clock.set(NOW.plusSeconds(31));Feeds.body.set(ics("Changed after preview"));refresh(path).then().statusCode(200);post("test-a",endpoint,input).then().statusCode(409);
        assertEquals(before,store.transaction("a",()->store.list("ACTIVITY").size()));
    }
    @Test void networkIoDoesNotHoldTheOwnerLockAndLateResponsesCannotResurrectDeletion()throws Exception{
        String path=subscribe(client("test-a").path("id").asText());Feeds.started=new CountDownLatch(1);Feeds.release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=executor.submit(()->refresh(path));assertTrue(Feeds.started.await(5,TimeUnit.SECONDS));
            var ownerRead=executor.submit(()->get("test-a","/me"));ownerRead.get(2,TimeUnit.SECONDS).then().statusCode(200);
            var current=get("test-a",path);call("test-a","DELETE",path,null,current.header("ETag"),null).then().statusCode(204);Feeds.release.countDown();pending.get(5,TimeUnit.SECONDS).then().statusCode(404);
            assertEquals(0,events().path("items").size());assertEquals(0,store.transaction("a",()->store.list(CalendarSubscriptions.FEED).size()));
        }
    }
    @Test void crossReplicaStyleClaimsFenceConcurrentRefreshAndConfigurationChanges(){
        String path=subscribe(client("test-a").path("id").asText());UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));var claim=store.transaction("a",()->new CalendarSubscriptions(store).claim(id,false));assertNotNull(claim);
        assertNull(store.transaction("a",()->new CalendarSubscriptions(store).claim(id,false)));refresh(path).then().statusCode(202);assertEquals(0,Feeds.requests.get());
        var current=get("test-a",path);var config=configuration(parse(current.asString()).path("clientId").asText()).put("url",Feeds.fixture.origin+"/calendar.ics?different=secret");call("test-a","PUT",path,config,current.header("ETag"),null).then().statusCode(200);
        store.transaction("a",()->{new CalendarSubscriptions(store).finish(claim,new CalendarDownload.Result(false,ics("Wrong retired source"),null,null),null);return null;});assertEquals(0,events().path("items").size());
    }
    @Test void simultaneousConversionCommandsAcrossIndependentConnectionsCreateOnlyOneRecordedOutcome()throws Exception{
        String path=subscribe(client("test-a").path("id").asText());refresh(path).then().statusCode(200);var event=events().path("items").get(0);var input=conversion(event);String endpoint=conversionPath(path,event);int before=store.transaction("a",()->store.list("ACTIVITY").size());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var first=executor.submit(()->{start.await();return post("test-a",endpoint,input);});var second=executor.submit(()->{start.await();return post("test-a",endpoint,input);});start.countDown();
            var statuses=List.of(first.get(5,TimeUnit.SECONDS).statusCode(),second.get(5,TimeUnit.SECONDS).statusCode());assertEquals(Set.of(201,409),new HashSet<>(statuses));
        }
        assertEquals(before+2,store.transaction("a",()->store.list("ACTIVITY").size()));
    }
    @Test void automaticRefreshHonoursItsFifteenMinuteDueTimeAndPreservesConditionalCaching()throws Exception{
        String path=subscribe(client("test-a").path("id").asText());refresher.sweep();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            while(!NOW.toString().equals(parse(get("test-a",path).asString()).path("lastSuccess").asText()))Thread.sleep(20);
        });assertEquals(1,Feeds.requests.get());
        var current=get("test-a",path);String client=parse(current.asString()).path("clientId").asText();
        var hidden=call("test-a","PUT",path,configuration(client).put("url","").put("visible",false),current.header("ETag"),null);hidden.then().statusCode(200);refresher.sweep();assertEquals(1,Feeds.requests.get());
        call("test-a","PUT",path,configuration(client).put("url","").put("visible",true),hidden.header("ETag"),null).then().statusCode(200);
        clock.set(NOW.plusSeconds(899));refresher.sweep();assertEquals(1,Feeds.requests.get());
        clock.set(NOW.plusSeconds(900));refresher.sweep();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            while(!NOW.plusSeconds(900).toString().equals(parse(get("test-a",path).asString()).path("lastSuccess").asText()))Thread.sleep(20);
        });assertEquals(2,Feeds.requests.get());assertEquals(1,events().path("items").size());
    }
    @Test void eventNotesArePrivateGuardedAndSurviveReschedulingAndSubscriptionRemoval(){
        String subscription=subscribe(client("test-a").path("id").asText());refresh(subscription).then().statusCode(200);
        var event=events().path("items").get(0);String path="/event-notes/"+event.path("subscriptionId").asText()+"/"+event.path("key").asText();
        String markdown="# Private minutes\r\n\r\n- [ ] Follow up  \r\n<https://example.org/a?q=1>\r\n---\r\n";
        var initial=get("test-a",path);initial.then().statusCode(200);assertEquals(0,initial.jsonPath().getLong("version"));
        var input=object().put("body",markdown).put("fromDate","2026-05-04").put("toDate","2026-05-11");
        call("test-a","PUT",path,input,null,null).then().statusCode(428);
        call("test-read","PUT",path,input,initial.header("ETag"),null).then().statusCode(403);
        call("test-a","PUT",path,input.deepCopy().put("body","ø".repeat(100001)),initial.header("ETag"),null).then().statusCode(422);
        var saved=call("test-a","PUT",path,input,initial.header("ETag"),null);saved.then().statusCode(200);
        assertEquals(markdown,saved.jsonPath().getString("body"));assertEquals("private, no-store",saved.header("Cache-Control"));
        assertEquals(markdown,get("test-read",path).jsonPath().getString("body"));assertEquals(0,get("test-b",path).jsonPath().getLong("version"));
        call("test-b","PUT",path,input,get("test-b",path).header("ETag"),null).then().statusCode(404);
        call("test-a","PUT",path,input,initial.header("ETag"),null).then().statusCode(412);
        assertTrue(events().path("items").get(0).path("hasNote").asBoolean());
        var listed=get("test-a","/event-notes");listed.then().statusCode(200);assertFalse(listed.asString().contains("Private minutes"));
        var download=get("test-a",path+"/markdown");download.then().statusCode(200);
        String filename=download.header("Content-Disposition").split("\"")[1];assertEquals(markdown,EventNotes.parse(filename,download.asByteArray()).body());assertFalse(filename.contains("Planning"));
        assertFalse(download.asString().contains("private-access"));
        String dav="/dav/journal/"+filename;
        var remote=given().auth().preemptive().basic("journal","test-a").get(dav);remote.then().statusCode(200);assertArrayEquals(download.asByteArray(),remote.asByteArray());
        given().auth().preemptive().basic("journal","test-b").get(dav).then().statusCode(404);
        given().auth().preemptive().basic("journal","test-a").contentType("text/markdown").body("changed").put(dav).then().statusCode(428);
        given().auth().preemptive().basic("journal","test-a").header("If-Match",initial.header("ETag")).contentType("text/markdown").body(remote.asByteArray()).put(dav).then().statusCode(412);
        given().auth().preemptive().basic("journal","test-a").header("If-Match",remote.header("ETag")).contentType("text/markdown").body(remote.asByteArray()).put(dav).then().statusCode(204);
        given().auth().preemptive().basic("journal","test-a").header("Depth","1").request("PROPFIND","/dav/journal/").then().statusCode(207).body(org.hamcrest.Matchers.containsString(filename));

        clock.set(NOW.plusSeconds(31));Feeds.body.set(ics("Renamed : / meeting").replace("20260504T215900Z","20260505T215900Z").replace("20260504T220100Z","20260505T220100Z"));refresh(subscription).then().statusCode(200);
        assertEquals(event.path("key"),events().path("items").get(0).path("key"));assertTrue(events().path("items").get(0).path("hasNote").asBoolean());assertEquals(markdown,get("test-a",path).jsonPath().getString("body"));
        call("test-a","DELETE",subscription,null,get("test-a",subscription).header("ETag"),null).then().statusCode(204);
        var edited=call("test-a","PUT",path,object().put("body","After subscription removal"),saved.header("ETag"),null);edited.then().statusCode(200);
        call("test-a","DELETE",path,null,edited.header("ETag"),null).then().statusCode(204);
        assertTrue(get("test-a",path).jsonPath().getBoolean("deleted"));get("test-a",path+"/markdown").then().statusCode(404);
        call("test-a","PUT",path,object().put("body",markdown),get("test-a",path).header("ETag"),null).then().statusCode(200);
    }

    @Test void recurringOccurrencesHaveSeparateNotes(){
        Feeds.body.set(ics("Recurring").replace("SUMMARY:","RRULE:FREQ=DAILY;COUNT=2\r\nSUMMARY:"));
        String subscription=subscribe(client("test-a").path("id").asText());refresh(subscription).then().statusCode(200);
        var items=events().path("items");assertEquals(2,items.size());assertNotEquals(items.get(0).path("key"),items.get(1).path("key"));
        String prefix="/event-notes/"+items.get(0).path("subscriptionId").asText()+"/",first=prefix+items.get(0).path("key").asText(),second=prefix+items.get(1).path("key").asText();
        call("test-a","PUT",first,object().put("body","First occurrence only").put("fromDate","2026-05-04").put("toDate","2026-05-11"),get("test-a",first).header("ETag"),null).then().statusCode(200);
        assertEquals(0,get("test-a",second).jsonPath().getLong("version"));
        var marked=events().path("items");assertTrue(marked.get(0).path("hasNote").asBoolean());assertFalse(marked.get(1).path("hasNote").asBoolean());
    }

}
