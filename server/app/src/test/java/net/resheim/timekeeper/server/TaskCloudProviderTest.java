package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.JsonNode;

class TaskCloudProviderTest {
    private TaskCloudProvider reader(TaskCloudProvider.Provider provider,TaskProviderHttp.Transport transport){
        return new TaskCloudProvider(provider,new TaskProviderHttp(Set.of("tasks.googleapis.com","openidconnect.googleapis.com","graph.microsoft.com"),transport));
    }
    private TaskCloudProvider reader(TaskCloudProvider.Provider provider){return reader(provider,request->{fail("Normalization must not call the provider");return null;});}
    private TaskProviderHttp.Reply reply(String json){return new TaskProviderHttp.Reply(200,Map.of(),json.getBytes(StandardCharsets.UTF_8));}
    private JsonNode json(String json){return JsonValues.parse(json);}
    private TaskCloudProvider google(){return reader(TaskCloudProvider.Provider.GOOGLE);}
    private TaskCloudProvider microsoft(){return reader(TaskCloudProvider.Provider.MICROSOFT);}

    @Test void googleReadsCompletedHiddenDeletedAndAssignedTasksAndEncodesOpaqueIds(){
        var requests=new ArrayList<String>();
        var provider=reader(TaskCloudProvider.Provider.GOOGLE,request->{
            requests.add(request.uri().toString());
            return requests.size()==1?reply("{\"items\":[{\"id\":\"one\",\"title\":\"Task\",\"status\":\"needsAction\"}],\"nextPageToken\":\"opaque+/=&? token\"}"):reply("{}");
        });
        var first=provider.tasks("private-token","list /?+#",null);
        var second=provider.tasks("private-token","list /?+#",first.next());
        assertEquals(1,first.items().size());assertTrue(second.items().isEmpty());assertNull(second.next());
        assertTrue(requests.getFirst().contains("lists/list%20%2F%3F%2B%23/tasks?"));
        for(String query:List.of("showCompleted=true","showHidden=true","showDeleted=true","showAssigned=true","maxResults=100"))assertTrue(requests.getFirst().contains(query));
        assertTrue(requests.get(1).contains("pageToken=opaque%2B%2F%3D%26%3F%20token"));
        assertFalse(requests.toString().contains("private-token"));
    }
    @Test void googleDueRemainsACivilScheduledDayWithoutInventingTimedFields(){
        var task=google().normalize(json("""
            {"id":"remote","title":"Review","notes":"Plain <notes>","status":"needsAction","due":"2026-11-09T00:00:00.000Z",
             "updated":"2026-11-08T10:20:30.123Z","etag":"\\\"provider-v2\\\"","parent":"parent-id","position":"00000000000000000001"}
            """));
        assertEquals("2026-11-09",task.fields().path("scheduledDate").asText());
        assertFalse(task.fields().has("due"));assertFalse(task.fields().has("start"));
        assertEquals("TEXT",task.fields().path("body").path("format").asText());assertEquals("Plain <notes>",task.fields().path("body").path("content").asText());
        assertEquals("parent-id",task.parent());assertEquals("00000000000000000001",task.position());
        assertEquals("2026-11-08T10:20:30.123Z",task.source().path("modifiedAt").asText());
        assertEquals("\"provider-v2\"",task.source().path("remoteEtag").asText());
    }
    @Test void googleAssignedTaskMissingNotesDoesNotClearLocalNotesOrPromoteSourceLinksToEditableUrl(){
        var original=json("""
            {"id":"assigned","title":"Assigned work","status":"needsAction","assignmentInfo":{"surfaceType":"DOCUMENT"},
             "links":[{"type":"email","link":"https://mail.google.com/mail/u/0/#inbox/id"}],"webViewLink":"https://tasks.google.com/id"}
            """);
        var task=google().normalize(original);
        assertFalse(task.fields().has("body"));assertFalse(task.fields().has("url"));assertEquals(original,task.original());
        assertFalse(google().capabilities().path("fields").has("url"));
    }
    @Test void googleAssignmentExposesReadOnlySourceContextWithoutInventingPersonIdentities(){
        var task=google().normalize(json("""
            {"id":"assigned","title":"Review","status":"needsAction","assignmentInfo":{"surfaceType":"DOCUMENT","linkToTask":"https://docs.google.com/document/d/file/edit#task-id","driveResourceInfo":{"driveFileId":"file","resourceKey":"opaque-key"}}}
            """));
        var assigned=task.fields().path("assignment");assertEquals("drive:file",assigned.path("sourceId").asText());assertEquals("https://docs.google.com/document/d/file/edit#task-id",assigned.path("sourceUrl").asText());assertFalse(assigned.path("writable").asBoolean());
        for(String field:List.of("assigneeId","assigneeName","assignedById","assignedByName"))assertFalse(assigned.hasNonNull(field));
        for(String field:List.of("assignment","metadata.links")){assertTrue(google().capabilities(true).path("fields").path(field).path("readable").asBoolean());assertFalse(google().capabilities(true).path("fields").path(field).path("writable").asBoolean());}
    }
    @Test void googleSourceLinksAreAccountScopedReadOnlyAndDoNotChangeThePrimaryUrl(){
        var task=google().normalize(json("""
            {"id":"task-id","title":"Review","status":"needsAction","webViewLink":"https://tasks.google.com/task-id","links":[{"type":"email","description":"Read source","link":"https://mail.google.com/mail/u/0/#inbox/message"}]}
            """));
        var one=GoogleTaskContext.fields(task,TaskCloudPull.repository("account-one","list"));var two=GoogleTaskContext.fields(task,TaskCloudPull.repository("account-two","list"));
        var link=one.path("metadata").path("links").get(0);assertEquals("PROVIDER_SOURCE",link.path("kind").asText());assertEquals("google",link.path("providerId").asText());assertFalse(one.has("url"));
        assertNotEquals(link.path("id"),two.path("metadata").path("links").get(0).path("id"));assertEquals(one,GoogleTaskContext.fields(task,TaskCloudPull.repository("account-one","list")));
    }
    @Test void malformedGoogleContextCannotBecomeANavigableLinkOrEraseCanonicalValues(){
        for(String fragment:List.of("\"webViewLink\":\"javascript:alert(1)\"","\"webViewLink\":\"https://user:secret@tasks.google.com/id\"","\"links\":[{\"link\":\"javascript:alert(1)\"}]","\"assignmentInfo\":{\"linkToTask\":\"http://docs.google.com/source\"}","\"links\":{}")){
            var failed=assertThrows(TaskProviderHttp.Failure.class,()->google().normalize(json("{\"id\":\"id\",\"title\":\"Title\",\"status\":\"needsAction\","+fragment+"}")));assertEquals("TASK_PROVIDER_INVALID_RESPONSE",failed.getMessage());assertFalse(failed.toString().contains("secret"));
        }
    }
    @Test void observedGoogleClearsAndCompletionAreExplicit(){
        var task=google().normalize(json("{\"id\":\"one\",\"title\":\"Done\",\"status\":\"completed\",\"completed\":\"2026-11-08T10:00:00Z\"}"));
        assertEquals("COMPLETED",task.fields().path("status").asText());assertEquals("2026-11-08T10:00:00Z",task.fields().path("completedAt").asText());
        assertTrue(task.fields().path("scheduledDate").isNull());assertEquals("",task.fields().path("body").path("content").asText());
        var incomplete=google().normalize(json("{\"id\":\"one\",\"title\":\"Reopened\",\"status\":\"needsAction\"}"));
        assertTrue(incomplete.fields().path("completedAt").isNull());
    }
    @Test void deletionDoesNotRequireOrManufactureTaskFields(){
        for(var provider:List.of(google(),microsoft())){
            var task=provider.normalize(json(provider.id().equals("google")?"{\"id\":\"gone\",\"deleted\":true}":"{\"id\":\"gone\",\"@removed\":{\"reason\":\"deleted\"}}"));
            assertTrue(task.deleted());assertTrue(task.fields().isEmpty());assertEquals("gone",task.id());
        }
    }
    @Test void microsoftRetainsHtmlWallTimesZonesAndIndependentCategories(){
        var original=json("""
            {"id":"graph-task","title":"Read plan","body":{"contentType":"html","content":"<p>Original <b>HTML</b></p>"},"status":"waitingOnOthers",
             "startDateTime":{"dateTime":"2026-11-09T08:30:00.0000000","timeZone":"W. Europe Standard Time"},
             "dueDateTime":{"dateTime":"2026-11-10T17:00:00.0000000","timeZone":"Europe/Oslo"},"importance":"high","categories":["Work"],
             "createdDateTime":"2026-10-01T12:00:00Z","lastModifiedDateTime":"2026-11-08T12:00:00Z","@odata.etag":"v2", "recurrence":{"pattern":{"type":"weekly"}}}
            """);
        var task=microsoft().normalize(original);var fields=task.fields();
        assertEquals("HTML",fields.path("body").path("format").asText());assertEquals(original.path("body").path("content"),fields.path("body").path("content"));
        assertEquals("08:30",fields.path("start").path("time").asText());assertEquals("W. Europe Standard Time",fields.path("start").path("timeZone").asText());
        assertEquals("Europe/Oslo",fields.path("due").path("timeZone").asText());assertEquals("WAITING",fields.path("status").asText());
        assertEquals("HIGH",fields.path("priority").asText());assertFalse(fields.has("flagged"));assertFalse(fields.has("urgent"));
        assertFalse(fields.path("metadata").has("tags"));assertFalse(fields.has("recurrence"));assertEquals(original,task.original());
        assertEquals("2026-10-01T12:00:00Z",task.source().path("createdAt").asText());
    }
    @Test void microsoftClearedFieldsDifferFromUnobservedFields(){
        var missing=microsoft().normalize(json("{\"id\":\"id\",\"title\":\"Minimal\",\"status\":\"notStarted\"}"));
        assertFalse(missing.fields().has("body"));assertFalse(missing.fields().has("due"));assertFalse(missing.fields().has("metadata"));
        var clear=microsoft().normalize(json("{\"id\":\"id\",\"title\":\"Clear\",\"status\":\"notStarted\",\"dueDateTime\":null,\"body\":null,\"categories\":[]}"));
        assertTrue(clear.fields().path("due").isNull());assertEquals("",clear.fields().path("body").path("content").asText());assertTrue(clear.fields().path("metadata").path("categories").isEmpty());
    }
    @Test void microsoftCompletionUsesAnUnambiguousInstant(){
        var task=microsoft().normalize(json("{\"id\":\"id\",\"title\":\"Done\",\"status\":\"completed\",\"completedDateTime\":{\"dateTime\":\"2026-11-09T12:00:00.0000000\",\"timeZone\":\"Europe/Oslo\"}}"));
        assertEquals("2026-11-09T11:00:00Z",task.fields().path("completedAt").asText());
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-25T02:30:00","2026-03-29T02:30:00"})
    void microsoftAmbiguousOrNonexistentCompletionIsNotSilentlyAssignedAnInstant(String time){
        assertThrows(TaskProviderHttp.Failure.class,()->microsoft().normalize(json("{\"id\":\"id\",\"title\":\"Done\",\"status\":\"completed\",\"completedDateTime\":{\"dateTime\":\""+time+"\",\"timeZone\":\"Europe/Oslo\"}}")));
    }
    @Test void unknownEnumsAndMalformedFieldsCannotBecomeDefaultOrEmptyObservations(){
        for(String value:List.of("{\"id\":\"id\",\"title\":\"Title\",\"status\":\"newFutureStatus\"}","{\"id\":\"id\",\"title\":\"Title\",\"status\":\"needsAction\",\"due\":\"wrong private date\"}","{\"id\":\"id\",\"title\":17,\"status\":\"needsAction\"}")){
            var failure=assertThrows(TaskProviderHttp.Failure.class,()->google().normalize(json(value)));
            assertEquals("TASK_PROVIDER_INVALID_RESPONSE",failure.getMessage());assertFalse(failure.toString().contains("private"));
        }
    }
    @Test void stableAccountIdentityDoesNotDependOnDisplayNameOrEmail(){
        var google=reader(TaskCloudProvider.Provider.GOOGLE,request->reply("{\"sub\":\"stable-google-sub\",\"name\":\"Display\",\"email\":\"mutable@example.invalid\"}"));
        var microsoft=reader(TaskCloudProvider.Provider.MICROSOFT,request->{assertEquals("/v1.0/me",request.uri().getPath());return reply("{\"id\":\"stable-graph-id\",\"displayName\":\"Display\",\"mail\":\"mutable@example.invalid\"}");});
        assertEquals("stable-google-sub",google.account("token").id());assertEquals("stable-graph-id",microsoft.account("token").id());
    }
    @Test void graphPaginationRetainsOpaqueQueryAndRejectsAnotherHostOrCollectionBeforeSending(){
        var calls=new ArrayList<String>();
        var provider=reader(TaskCloudProvider.Provider.MICROSOFT,request->{calls.add(request.uri().toString());return reply("{\"value\":[],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/todo/lists/list/tasks?$skiptoken=opaque%2B%2F%3D\"}");});
        var page=provider.tasks("token","list",null);provider.tasks("token","list",page.next());
        assertTrue(calls.get(1).endsWith("?$skiptoken=opaque%2B%2F%3D"));
        for(String link:List.of("https://evil.invalid/v1.0/me/todo/lists/list/tasks","https://graph.microsoft.com/v1.0/me/messages","https://graph.microsoft.com/v1.0/me/todo/lists/other/tasks"))
            assertThrows(TaskProviderHttp.Failure.class,()->provider.tasks("token","list",link));
        assertEquals(2,calls.size());
    }
    @Test void graphCannotReturnAnUntrustedNextLinkOrPretendMalformedCollectionsAreEmpty(){
        for(String response:List.of("{}","{\"value\":{}}","{\"value\":[],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/messages\"}")){
            var provider=reader(TaskCloudProvider.Provider.MICROSOFT,request->reply(response));
            assertThrows(TaskProviderHttp.Failure.class,()->provider.tasks("token","list",null));
        }
    }
    @Test void listMetadataPreservesSharingAndBuiltInClassification(){
        var provider=reader(TaskCloudProvider.Provider.MICROSOFT,request->reply("{\"value\":[{\"id\":\"shared\",\"displayName\":\"Team\",\"isShared\":true,\"isOwner\":false,\"wellknownListName\":\"flaggedEmails\"}]}"));
        var list=provider.lists("token",null).items().getFirst();
        assertTrue(list.shared());assertFalse(list.owner());assertEquals("BUILT_IN",list.kind());
        assertEquals("flaggedEmails",list.original().path("wellknownListName").asText());
    }
    @Test void readerNeverAdvertisesProviderWritesOrUntestedFields(){
        for(var provider:List.of(google(),microsoft())){
            provider.capabilities().path("fields").forEach(field->{assertTrue(field.path("readable").asBoolean());assertFalse(field.path("writable").asBoolean());assertFalse(field.path("deletable").asBoolean());});
            for(String field:List.of("flagged","urgent","recurrence.series","attachments","reminders.ABSOLUTE"))assertFalse(provider.capabilities().path("fields").has(field));
        }
    }
    @Test void googleTitleLimitCountsUnicodeCharactersAndRetainsOpaqueIdentityValidation(){
        String title="😀".repeat(1024);var value=JsonValues.object().put("id","task-id").put("title",title).put("status","needsAction");
        assertEquals(title,google().normalize(value).fields().path("summary").asText());
        assertThrows(TaskProviderHttp.Failure.class,()->google().normalize(value.deepCopy().put("title",title+"x")));
        assertThrows(TaskProviderHttp.Failure.class,()->google().normalize(value.deepCopy().put("id","x".repeat(1025))));
    }

}
