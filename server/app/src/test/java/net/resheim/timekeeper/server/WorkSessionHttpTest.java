package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.quarkus.test.junit.*;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import net.resheim.eclipse.timekeeper.client.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(WorkSessionHttpTest.Profile.class)
class WorkSessionHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_http_tests");}
    }
    @TestHTTPResource URI endpoint;
    @Inject ServerStore store;
    @Inject MockBackendClock clock;
    private HttpTimekeeperService client(String token) {return new HttpTimekeeperService(endpoint,()->token,Duration.ofSeconds(5));}
    @AfterEach void clear() throws Exception {
        clock.set(null);
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);
        try(var connection=actual.datasource.getConnection()) {
            connection.setAutoCommit(false);
            for(var table:List.of("tk_auth_session","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner"))
                try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM "+table+" WHERE "+(table.equals("tk_owner") ? "id" : "owner_id")+" IN ('a','b')");}
            connection.commit();
        }
    }
    @Test void projectsOverlapRetrospectiveEditsAndIndependentTimersRoundTripThroughTheRealClient() {
        try(var api=client("test-a");var peer=client("test-a");var foreign=client("test-b");var read=client("test-read")) {
            var from=Instant.parse("2025-10-25T22:00:00Z");var end=Instant.parse("2025-10-26T23:00:00Z");
            var session=api.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,Optional.of(end),"Across midnight"));
            assertEquals(WorkSession.State.COMPLETED,session.state());assertEquals(session,api.workSession(session.id()).orElseThrow());
            var firstProject=api.createProject(new CreateProject("First project"));var secondProject=api.createProject(new CreateProject("Second project"));
            var firstTask=api.createTask(new CreateTask(Optional.of(firstProject.id()),Optional.empty(),"Jira task",Optional.empty(),Set.of(
                    new ExternalTaskReference("jira","https://jira.example","SESSION-1",Optional.empty()))));
            var secondTask=api.createTask(new CreateTask(Optional.of(secondProject.id()),Optional.empty(),"GitHub task",Optional.empty(),Set.of(
                    new ExternalTaskReference("github","https://github.example/repo","1",Optional.empty()))));
            var a=api.createActivity(new CreateActivity(firstTask.id(),OwnerId.LOCAL,Instant.parse("2025-10-26T08:00:00Z"),Optional.of(Instant.parse("2025-10-26T12:00:00Z")),"First",Set.of()));
            var b=api.createActivity(new CreateActivity(secondTask.id(),OwnerId.LOCAL,Instant.parse("2025-10-26T10:00:00Z"),Optional.of(Instant.parse("2025-10-26T14:00:00Z")),"Second",Set.of()));
            assertEquals(RemoteFailure.Kind.CONFLICT,assertThrows(RemoteFailure.class,()->api.associateWorkSession(new AssociateWorkSession(a.id(),a.version(),Optional.of(session.id())))).kind());
            assertEquals(a,api.associateWorkSession(new AssociateWorkSession(a.id(),a.version(),Optional.empty())).activity());
            var report=api.workSessionReport(session.id());
            assertEquals(Duration.ofHours(25),report.time().duration());assertEquals(Duration.ofHours(8),report.time().trackedActivityTime());
            assertEquals(Duration.ofHours(6),report.time().allocatedTime());assertEquals(Duration.ofHours(19),report.time().unallocatedTime());
            var edited=api.updateActivity(new UpdateActivity(a.id(),a.version(),a.start(),a.end(),"Edited later",a.labelIds()));
            assertTrue(edited.workSessionId().isEmpty());assertEquals(edited,peer.activity(a.id()).orElseThrow());
            assertEquals(firstTask.externalReferences(),api.task(firstTask.id()).orElseThrow().externalReferences());
            assertEquals(secondTask.externalReferences(),api.task(secondTask.id()).orElseThrow().externalReferences());
            var outside=api.createActivity(new CreateActivity(firstTask.id(),OwnerId.LOCAL,end.plusSeconds(60),Optional.of(end.plusSeconds(120)),"Outside",Set.of()));
            assertFalse(api.workSessionReport(session.id()).activities().stream().anyMatch(value->value.id().equals(outside.id())));
            assertTrue(api.workSessionReport(session.id()).time().outsideSession().isEmpty());
            assertEquals(Duration.ofHours(19),api.workSessionReport(session.id()).time().unallocatedTime());
            var changed=peer.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),from,Optional.of(end.plusSeconds(30)),"Adjusted session"));
            assertThrows(RemoteFailure.class,()->api.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),from,Optional.of(end),"Stale")));
            assertEquals(changed,api.workSession(session.id()).orElseThrow());assertTrue(foreign.workSession(session.id()).isEmpty());
            assertThrows(RemoteFailure.class,()->read.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Read only")));
            var runningActivity=api.startActivity(new StartActivity(firstTask.id(),OwnerId.LOCAL,"Live activity",Set.of()));
            var runningSession=api.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Live session"));
            assertThrows(RemoteFailure.class,()->peer.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Duplicate")));
            api.stopWorkSession(new StopWorkSession(runningSession.id(),runningSession.version()));
            assertEquals(runningActivity,api.activeActivity(OwnerId.LOCAL).orElseThrow());
            var another=api.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Same day again"));
            api.stopActivity(new StopActivity(runningActivity.id(),runningActivity.version()));
            assertEquals(another,api.activeWorkSession(OwnerId.LOCAL).orElseThrow());
            assertEquals(3,api.workSessions(OwnerId.LOCAL).size());
        }
    }
    @Test void tasklessActivitiesIgnoreLegacySessionHintsAndReportTemporalContextAcrossMidnight() {
        try(var api=client("test-a")) {
            var from=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
            var session=api.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,Optional.of(end),"Unassigned work"));
            var label=api.createLabel(new CreateLabel("Meeting",Optional.of("12,34,56")));
            var retained=api.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"No task",Set.of(label.id()),Optional.of(session.id()),Optional.empty()));
            assertTrue(retained.task().isEmpty());assertTrue(retained.projectId().isEmpty());
            assertTrue(retained.workSessionId().isEmpty());assertTrue(api.tasks().isEmpty());
            var query=new net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery(from,end.plusSeconds(1),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),ZoneId.of("UTC"));
            var report=api.activities(query);assertEquals(3,report.activities().size());assertEquals(Duration.between(from,end),report.total());
            for(var activity:report.activities()) {assertTrue(activity.task().isEmpty());assertTrue(activity.workSessionId().isEmpty());assertEquals(Set.of(label.id()),activity.labelIds());}
            assertEquals(Duration.ZERO,api.workSessionReport(session.id()).time().unallocatedTime());
            var project=api.createProject(new CreateProject("Direct project"));
            var direct=api.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.plusSeconds(1),Optional.of(from.plusSeconds(61)),"Project without task",Set.of(),Optional.empty(),Optional.of(project.id())));
            assertTrue(direct.task().isEmpty());assertEquals(Optional.of(project.id()),api.activity(direct.id()).orElseThrow().projectId());
            assertThrows(RemoteFailure.class,()->api.deleteProject(new DeleteProject(project.id(),project.version())));
            var projectQuery=new net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery(from,end.plusSeconds(1),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.of(project.id()),ZoneId.of("UTC"));
            assertEquals(Duration.ofMinutes(1),api.activities(projectQuery).total());
            var independent=api.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Unknown legacy session hint",Set.of(),Optional.of(WorkSessionId.random()),Optional.empty()));
            assertTrue(independent.workSessionId().isEmpty());
            var running=api.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Live work"));
            var live=api.startActivity(new StartActivity(null,OwnerId.LOCAL,"No task timer",Set.of(label.id()),Optional.of(running.id()),Optional.empty()));
            assertTrue(live.task().isEmpty());assertTrue(live.workSessionId().isEmpty());
            assertEquals(live,api.activeActivity(OwnerId.LOCAL).orElseThrow());
            api.stopWorkSession(new StopWorkSession(running.id(),running.version()));
            assertEquals(live,api.activeActivity(OwnerId.LOCAL).orElseThrow());
            var stopped=api.stopActivity(new StopActivity(live.id(),live.version()));assertEquals(live.workSessionId(),stopped.workSessionId());
        }
    }
    @Test void rawHttpCommandsReplayOriginalStartAndStopRepliesAndValidateVersions() {
        String key=UUID.randomUUID().toString(), body="{\"summary\":\"Durable session\"}";
        var started=given().header("Authorization","Bearer test-a").header("Idempotency-Key",key).contentType("application/json").body(body)
                .post("/api/v1/work-sessions/start").then().statusCode(201).extract().response();
        String id=started.jsonPath().getString("id"),tag=started.header("ETag");
        var replay=given().header("Authorization","Bearer test-a").header("Idempotency-Key",key).contentType("application/json").body(body)
                .post("/api/v1/work-sessions/start").then().statusCode(201).extract().response();
        assertEquals(started.asString(),replay.asString());assertEquals(tag,replay.header("ETag"));
        given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"start\":\"2025-01-01T00:00:00Z\",\"end\":null,\"summary\":\"Unconditional\"}")
                .put("/api/v1/work-sessions/"+id).then().statusCode(428);
        String stopKey=UUID.randomUUID().toString();
        var stopped=given().header("Authorization","Bearer test-a").header("Idempotency-Key",stopKey).header("If-Match",tag).contentType("application/json")
                .post("/api/v1/work-sessions/"+id+"/stop").then().statusCode(200).extract().response();
        var repeated=given().header("Authorization","Bearer test-a").header("Idempotency-Key",stopKey).header("If-Match",tag).contentType("application/json")
                .post("/api/v1/work-sessions/"+id+"/stop").then().statusCode(200).extract().response();
        assertEquals(stopped.asString(),repeated.asString());assertEquals(stopped.header("ETag"),repeated.header("ETag"));
        var original=given().header("Authorization","Bearer test-a").header("Idempotency-Key",key).contentType("application/json").body(body)
                .post("/api/v1/work-sessions/start").then().statusCode(201).extract().response();
        assertEquals(started.asString(),original.asString());
        given().header("Authorization","Bearer test-a").get("/api/v1/work-sessions/active").then().statusCode(204);
        given().header("Authorization","Bearer test-a").header("Idempotency-Key",key).contentType("application/json").body("{\"summary\":\"Different\"}")
                .post("/api/v1/work-sessions/start").then().statusCode(409);
        given().header("Authorization","Bearer test-read").header("If-Match",stopped.header("ETag")).delete("/api/v1/work-sessions/"+id).then().statusCode(403);
        given().header("Authorization","Bearer test-b").get("/api/v1/work-sessions/"+id+"/report").then().statusCode(404);
        given().get("/api/v1/work-sessions").then().statusCode(401);
        given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"start\":\"2099-01-01T00:00:00Z\",\"end\":null,\"summary\":\"Future\"}")
                .post("/api/v1/work-sessions").then().statusCode(422);
        given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"start\":\"2025-01-01T00:00:00Z\",\"end\":null,\"summary\":\"Injected\",\"ownerId\":\"b\"}")
                .post("/api/v1/work-sessions").then().statusCode(400);
        try(var api=client("test-a")) {assertEquals(1,api.workSessions(OwnerId.LOCAL).size());}
    }
    private io.restassured.response.Response createSession() {
        return given().header("Authorization","Bearer test-a").contentType("application/json")
                .body("{\"start\":\"2025-01-01T08:00:00.123456789Z\",\"end\":\"2025-01-01T16:00:00.987654321Z\",\"summary\":\"Original\"}")
                .post("/api/v1/work-sessions").then().statusCode(201).extract().response();
    }
    private io.restassured.response.Response editSession(io.restassured.response.Response current,String summary) {
        var input=JsonValues.object().put("start",current.jsonPath().getString("start")).put("end",current.jsonPath().getString("end")).put("summary",summary);
        return given().header("Authorization","Bearer test-a").header("If-Match",current.header("ETag")).header("X-Timekeeper-Undo","true")
                .contentType("application/json").body(input.toString()).put("/api/v1/work-sessions/"+current.jsonPath().getString("id"))
                .then().statusCode(200).extract().response();
    }
    private io.restassured.response.Response undo(String id,String token,String key,int status) {
        return given().header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").body("{}")
                .post("/api/v1/undo/"+id).then().statusCode(status).extract().response();
    }
    @Test void undoRestoresExactSessionAndReplaysWithoutRepeatingTheWrite() throws Exception {
        var original=createSession();var changed=editSession(original,"Changed");String action=changed.header("X-Timekeeper-Undo-Id"),key=UUID.randomUUID().toString();
        assertNotNull(action);assertEquals("600",changed.header("X-Timekeeper-Undo-Seconds"));
        var restored=undo(action,"test-a",key,200);assertEquals(original.jsonPath().getString("start"),restored.jsonPath().getString("session.start"));
        assertEquals(original.jsonPath().getString("end"),restored.jsonPath().getString("session.end"));assertEquals("Original",restored.jsonPath().getString("session.summary"));
        assertEquals(2,restored.jsonPath().getInt("session.version"));
        assertEquals(restored.asString(),undo(action,"test-a",key,200).asString());
        assertEquals(restored.asString(),undo(action,"test-a",UUID.randomUUID().toString(),200).asString());
        given().header("Authorization","Bearer test-a").header("If-Match",original.header("ETag")).delete("/api/v1/work-sessions/"+original.jsonPath().getString("id")).then().statusCode(412);
    }
    @Test void undoDeletionRestoresIdentityAndReferencesWithoutChangingActivities() {
        var original=createSession();String id=original.jsonPath().getString("id");
        var activity=given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"taskId\":null,\"start\":\"2025-01-01T10:00:00Z\",\"end\":\"2025-01-01T11:00:00Z\",\"summary\":\"Independent\",\"labelIds\":[]}")
                .post("/api/v1/activities").then().statusCode(201).extract().response();
        var removed=given().header("Authorization","Bearer test-a").header("If-Match",original.header("ETag")).header("X-Timekeeper-Undo","true").delete("/api/v1/work-sessions/"+id).then().statusCode(204).extract().response();
        var restored=undo(removed.header("X-Timekeeper-Undo-Id"),"test-a",UUID.randomUUID().toString(),200);
        assertEquals(id,restored.jsonPath().getString("session.id"));assertEquals(1,restored.jsonPath().getInt("session.version"));
        var actual=given().header("Authorization","Bearer test-a").get("/api/v1/activities/"+activity.jsonPath().getString("id")).then().statusCode(200).extract().response();
        assertEquals(activity.asString(),actual.asString());
        // Reconstructed client relation still protects the destination from deletion.
        String client=restored.jsonPath().getString("session.clientId");var loaded=given().header("Authorization","Bearer test-a").get("/api/v1/clients/"+client).then().statusCode(200).extract().response();
        given().header("Authorization","Bearer test-a").header("If-Match",loaded.header("ETag")).delete("/api/v1/clients/"+client).then().statusCode(409);
    }
    @Test void undoIsOwnerScopedClosedAndCannotOverwriteAPeerEdit() {
        var changed=editSession(createSession(),"First change");String action=changed.header("X-Timekeeper-Undo-Id");
        undo(action,"test-b",UUID.randomUUID().toString(),404);undo(action,"test-read",UUID.randomUUID().toString(),403);
        given().header("Authorization","Bearer test-a").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").body("{\"ownerId\":\"b\"}").post("/api/v1/undo/"+action).then().statusCode(400);
        var peer=editSession(changed,"Peer change");undo(action,"test-a",UUID.randomUUID().toString(),409);
        assertEquals(peer.asString(),given().header("Authorization","Bearer test-a").get("/api/v1/work-sessions/"+peer.jsonPath().getString("id")).then().statusCode(200).extract().asString());
    }
    @Test void expiredUndoAndMissingClientCannotRestoreDeletedSession() {
        var original=createSession();var changed=editSession(original,"Changed");
        clock.set(Instant.now().plusSeconds(601));undo(changed.header("X-Timekeeper-Undo-Id"),"test-a",UUID.randomUUID().toString(),410);clock.set(null);
        String id=changed.jsonPath().getString("id"),client=changed.jsonPath().getString("clientId");
        var deleted=given().header("Authorization","Bearer test-a").header("If-Match",changed.header("ETag")).header("X-Timekeeper-Undo","true").delete("/api/v1/work-sessions/"+id).then().statusCode(204).extract().response();
        var loaded=given().header("Authorization","Bearer test-a").get("/api/v1/clients/"+client).then().statusCode(200).extract().response();
        given().header("Authorization","Bearer test-a").header("If-Match",loaded.header("ETag")).delete("/api/v1/clients/"+client).then().statusCode(204);
        undo(deleted.header("X-Timekeeper-Undo-Id"),"test-a",UUID.randomUUID().toString(),409);
        given().header("Authorization","Bearer test-a").get("/api/v1/work-sessions/"+id).then().statusCode(404);
    }

    @Test void undoRestoresAllMetadataAndRejectsARunningSessionCollision() {
        try(var api=client("test-a")) {
            var project=api.createProject(new CreateProject("Undo project"));var label=api.createLabel(new CreateLabel("Undo label",Optional.of("12,34,56")));
            var original=createSession();var input=JsonValues.object().put("start",original.jsonPath().getString("start")).put("end",original.jsonPath().getString("end")).put("summary","With metadata").put("projectId",project.id().value().toString());input.putArray("labelIds").add(label.id().value().toString());
            var tagged=given().header("Authorization","Bearer test-a").header("If-Match",original.header("ETag")).contentType("application/json").body(input.toString())
                    .put("/api/v1/work-sessions/"+original.jsonPath().getString("id")).then().statusCode(200).extract().response();
            assertNull(tagged.header("X-Timekeeper-Undo-Id"));
            var removed=given().header("Authorization","Bearer test-a").header("If-Match",tagged.header("ETag")).header("X-Timekeeper-Undo","true").delete("/api/v1/work-sessions/"+tagged.jsonPath().getString("id")).then().statusCode(204).extract().response();
            var restored=undo(removed.header("X-Timekeeper-Undo-Id"),"test-a",UUID.randomUUID().toString(),200);
            var expected=(com.fasterxml.jackson.databind.node.ObjectNode)JsonValues.parse(tagged.asString());expected.put("version",expected.path("version").asLong()+1);
            assertEquals(expected,JsonValues.parse(restored.asString()).path("session"));
            assertThrows(RemoteFailure.class,()->api.deleteLabel(new DeleteLabel(label.id(),label.version())));
            var running=api.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"First running"));
            var loaded=given().header("Authorization","Bearer test-a").get("/api/v1/work-sessions/"+running.id().value()).then().statusCode(200).extract().response();
            var deleted=given().header("Authorization","Bearer test-a").header("If-Match",loaded.header("ETag")).header("X-Timekeeper-Undo","true").delete("/api/v1/work-sessions/"+running.id().value()).then().statusCode(204).extract().response();
            var second=api.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Second running"));
            undo(deleted.header("X-Timekeeper-Undo-Id"),"test-a",UUID.randomUUID().toString(),409);
            assertEquals(second,api.activeWorkSession(OwnerId.LOCAL).orElseThrow());assertTrue(api.workSession(running.id()).isEmpty());
        }
    }

}
