package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;

@Path("/api/v1") @Blocking @Consumes("application/json") @Produces("application/json")
public class TimekeeperResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Inject Pages pages;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> work) { return store.authenticated(identity,() -> work.get().response()); }
    private static ApiReplies json(JsonNode value) { return new ApiReplies(200,value,null,null); }
    private static ApiReplies empty() { return new ApiReplies(204,null,null,null); }
    private JsonNode required(String type,UUID id) { return store.find(type,id).orElseThrow(ApiFailure::notFound); }
    private static Optional<ProjectId> project(JsonNode input) { return optionalText(input.get("projectId")).map(UUID::fromString).map(ProjectId::new); }
    private static Optional<TaskId> parent(JsonNode input) { return optionalText(input.get("parentTaskId")).map(UUID::fromString).map(TaskId::new); }
    @GET @Path("/openapi.json") public JsonNode openApi() { return inputs.contract(); }
    @GET @Path("/{collection}/{id}") public Response get(@PathParam("collection") String collection,@PathParam("id") String id) {
        String type=ContractInputs.type(collection);UUID key=ContractInputs.pathId(id);
        ContractInputs.queries(uri.getQueryParameters());return tx(() -> ApiReplies.resource(200,required(type,key),null));
    }
    @POST @Path("/{collection}") public Response create(@PathParam("collection") String collection,JsonNode body) {
        String type=ContractInputs.type(collection);JsonNode input=inputs.validate(ContractInputs.input(type,true),body);
        ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {
            if(type.equals("CLIENT")) {var node=store.createClient(input);return ApiReplies.resource(201,node,"/api/v1/clients/"+node.get("id").asText());}
            var service=store.service();Object value=switch(type) {
                case "PROJECT" -> service.createProject(new CreateProject(input.get("name").asText()));
                case "TASK" -> service.createTask(new CreateTask(project(input),parent(input),input.get("summary").asText(),optionalText(input.get("url")),references(input.get("externalReferences"))));
                case "LABEL" -> service.createLabel(new CreateLabel(input.get("name").asText(),optionalText(input.get("color"))));
                case "ACTIVITY" -> service.createActivity(new CreateActivity(optionalText(input.get("taskId")).map(UUID::fromString).map(TaskId::new).orElse(null),new OwnerId(store.owner()),Instant.parse(input.get("start").asText()),optionalText(input.get("end")).map(Instant::parse),input.get("summary").asText(),labels(input.get("labelIds")),optionalText(input.get("workSessionId")).map(UUID::fromString).map(WorkSessionId::new),project(input)));
                case "WORK_SESSION" -> store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(store.owner()),Instant.parse(input.get("start").asText()),optionalText(input.get("end")).map(Instant::parse),input.get("summary").asText()));
                default -> throw ApiFailure.notFound();
            };
            JsonNode snapshot=stored(type,value,input);return ApiReplies.resource(201,snapshot,"/api/v1/"+collection+"/"+snapshot.get("id").asText());
        });
    }
    @PUT @Path("/{collection}/{id}") public Response update(@PathParam("collection") String collection,@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        String type=ContractInputs.type(collection);UUID key=ContractInputs.pathId(id);JsonNode input=inputs.validate(ContractInputs.input(type,false),body);
        ContractInputs.queries(uri.getQueryParameters());return tx(() -> {
            long version=ApiReplies.match(required(type,key),match);
            if(type.equals("CLIENT")) return ApiReplies.resource(200,store.updateClient(key,version,input),null);
            var service=store.service();Object value=switch(type) {
                case "PROJECT" -> service.updateProject(new UpdateProject(new ProjectId(key),version,input.get("name").asText()));
                case "TASK" -> service.updateTask(new UpdateTask(new TaskId(key),version,project(input),parent(input),input.get("summary").asText(),optionalText(input.get("url"))));
                case "LABEL" -> service.updateLabel(new UpdateLabel(new LabelId(key),version,input.get("name").asText(),optionalText(input.get("color"))));
                case "ACTIVITY" -> service.updateActivity(new UpdateActivity(new ActivityId(key),version,Instant.parse(input.get("start").asText()),optionalText(input.get("end")).map(Instant::parse),input.get("summary").asText(),labels(input.get("labelIds"))));
                case "WORK_SESSION" -> store.workSessionService().updateWorkSession(new UpdateWorkSession(new WorkSessionId(key),version,Instant.parse(input.get("start").asText()),optionalText(input.get("end")).map(Instant::parse),input.get("summary").asText()));
                default -> throw ApiFailure.notFound();
            };return ApiReplies.resource(200,stored(type,value,input),null);
        });
    }
    @DELETE @Path("/{collection}/{id}") public Response delete(@PathParam("collection") String collection,@PathParam("id") String id,@HeaderParam("If-Match") String match) {
        String type=ContractInputs.type(collection);UUID key=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {
            long version=ApiReplies.match(required(type,key),match);var service=store.service();
            switch(type) {
                case "CLIENT" -> {store.delete(type,key);store.change(type,"DELETED",key.toString());}
                case "PROJECT" -> service.deleteProject(new DeleteProject(new ProjectId(key),version));
                case "TASK" -> service.deleteTask(new DeleteTask(new TaskId(key),version));
                case "LABEL" -> service.deleteLabel(new DeleteLabel(new LabelId(key),version));
                case "ACTIVITY" -> service.deleteActivity(new DeleteActivity(new ActivityId(key),version));
                case "WORK_SESSION" -> store.workSessionService().deleteWorkSession(new DeleteWorkSession(new WorkSessionId(key),version));
                default -> throw ApiFailure.notFound();
            }return empty();
        });
    }
    private JsonNode stored(String type,Object value,JsonNode input) {
        var node=snapshot(store.owner(),value);
        return Set.of("PROJECT","WORK_SESSION").contains(type) ? store.clientDetails(type,uuid(node.get("id")),input) : node;
    }
    private JsonNode sessionSnapshot(WorkSession value) {return required("WORK_SESSION",value.id().value());}
    @GET @Path("/tasks/by-external-reference") public Response external() {
        ContractInputs.queries(uri.getQueryParameters(),"providerId","repositoryId","externalId");var query=uri.getQueryParameters();
        var ref=referenceQuery(query);return tx(() -> ApiReplies.resource(200,snapshot(store.owner(),store.service().findTask(ref).orElseThrow(ApiFailure::notFound)),null));
    }
    private ExternalTaskReferenceKey referenceQuery(MultivaluedMap<String,String> query) {
        var value=object();for(String key:List.of("providerId","repositoryId","externalId")) { if(query.getFirst(key)==null) throw ApiFailure.badRequest();value.put(key,query.getFirst(key)); }
        value.putNull("externalUrl");inputs.validate("ExternalReference",value);return reference(value).key();
    }
    @PUT @Path("/tasks/{id}/external-references") public Response link(@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        UUID key=ContractInputs.pathId(id);JsonNode input=inputs.validate("ExternalReference",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> { long version=ApiReplies.match(required("TASK",key),match);return ApiReplies.resource(200,snapshot(store.owner(),store.service().linkExternalReference(new LinkExternalReference(new TaskId(key),version,reference(input)))),null); });
    }
    @DELETE @Path("/tasks/{id}/external-references") public Response unlink(@PathParam("id") String id,@HeaderParam("If-Match") String match) {
        UUID key=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters(),"providerId","repositoryId","externalId");var reference=referenceQuery(uri.getQueryParameters());
        return tx(() -> { long version=ApiReplies.match(required("TASK",key),match);return ApiReplies.resource(200,snapshot(store.owner(),store.service().unlinkExternalReference(new UnlinkExternalReference(new TaskId(key),version,reference))),null); });
    }
    @GET @Path("/activities/active") public Response active() {
        ContractInputs.queries(uri.getQueryParameters());return tx(() -> store.active().map(value -> ApiReplies.resource(200,value,null)).orElseGet(TimekeeperResource::empty));
    }
    @POST @Path("/activities/start") public Response start(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        JsonNode input=inputs.validate("StartActivityInput",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> receipts.execute(key,"POST","/activities/start",null,input,() -> {
            var active=store.active();var expectedId=input.get("expectedActiveId");var expectedVersion=input.get("expectedActiveVersion");
            if(expectedId.isNull()!=expectedVersion.isNull()) throw ApiFailure.validation();
            if((active.isEmpty() && !expectedId.isNull()) || (active.isPresent() && (expectedId.isNull() || !active.get().get("id").asText().equals(uuid(expectedId).toString()) || active.get().get("version").asLong()!=expectedVersion.asLong())))
                throw new ApiFailure(409,"ACTIVE_STATE_CHANGED");
            UUID task=optionalText(input.get("taskId")).map(UUID::fromString).orElse(null);if(task!=null) required("TASK",task);for(LabelId label:labels(input.get("labelIds"))) required("LABEL",label.value());
            if(task!=null && active.isPresent() && active.get().get("taskId").asText().equals(task.toString())) return ApiReplies.resource(200,active.get(),null);
            var service=store.service();if(active.isPresent()) service.stopActivity(new StopActivity(new ActivityId(uuid(active.get().get("id"))),active.get().get("version").asLong()));
            var created=service.startActivity(new StartActivity(task==null ? null : new TaskId(task),new OwnerId(store.owner()),input.get("summary").asText(),labels(input.get("labelIds")),optionalText(input.get("workSessionId")).map(UUID::fromString).map(WorkSessionId::new),project(input)));
            return ApiReplies.resource(201,snapshot(store.owner(),created),"/api/v1/activities/"+created.id().value());
        }));
    }
    @POST @Path("/activities/{id}/stop") public Response stop(@PathParam("id") String id,@HeaderParam("If-Match") String match,@HeaderParam("Idempotency-Key") String key) {
        UUID activity=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> receipts.execute(key,"POST","/activities/"+activity+"/stop",match,null,() -> {
            JsonNode current=required("ACTIVITY",activity);long version=ApiReplies.match(current,match);
            if(!current.get("end").isNull()) return ApiReplies.resource(200,current,null);
            return ApiReplies.resource(200,snapshot(store.owner(),store.service().stopActivity(new StopActivity(new ActivityId(activity),version))),null);
        }));
    }
    @GET @Path("/work-sessions/active") public Response activeWorkSession() {
        ContractInputs.queries(uri.getQueryParameters());return tx(() -> store.workSessionService().activeWorkSession(new OwnerId(store.owner()))
                .map(value -> ApiReplies.resource(200,sessionSnapshot(value),null)).orElseGet(TimekeeperResource::empty));
    }
    @POST @Path("/work-sessions/start") public Response startWorkSession(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        var input=inputs.validate("StartWorkSessionInput",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> receipts.execute(key,"POST","/work-sessions/start",null,input,() -> {
            var value=store.workSessionService().startWorkSession(new StartWorkSession(new OwnerId(store.owner()),input.get("summary").asText()));
            return ApiReplies.resource(201,store.clientDetails("WORK_SESSION",value.id().value(),input),"/api/v1/work-sessions/"+value.id().value());
        }));
    }
    @POST @Path("/work-sessions/{id}/stop") public Response stopWorkSession(@PathParam("id") String id,@HeaderParam("If-Match") String match,@HeaderParam("Idempotency-Key") String key) {
        UUID session=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> receipts.execute(key,"POST","/work-sessions/"+session+"/stop",match,null,() -> {
            var current=required("WORK_SESSION",session);long version=ApiReplies.match(current,match);
            return ApiReplies.resource(200,sessionSnapshot(store.workSessionService().stopWorkSession(new StopWorkSession(new WorkSessionId(session),version))),null);
        }));
    }
    @PUT @Path("/activities/{id}/work-session") public Response associateWorkSession(@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        UUID activity=ContractInputs.pathId(id);var input=inputs.validate("WorkSessionAssociationInput",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {
            long version=ApiReplies.match(required("ACTIVITY",activity),match);
            var result=store.workSessionService().associateWorkSession(new AssociateWorkSession(new ActivityId(activity),version,
                    optionalText(input.get("workSessionId")).map(UUID::fromString).map(WorkSessionId::new)));
            var value=snapshot(store.owner(),result.activity());var response=object();response.set("activity",value);response.put("outsideSession",result.outsideSession());
            return new ApiReplies(200,response,etag(value),null);
        });
    }
    @GET @Path("/work-sessions/{id}/report") public Response workSessionReport(@PathParam("id") String id) {
        var session=new WorkSessionId(ContractInputs.pathId(id));ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {
            var report=store.workSessionService().workSessionReport(session);var response=object();response.set("session",sessionSnapshot(report.session()));
            var activities=response.putArray("activities");report.activities().forEach(value -> activities.add(snapshot(store.owner(),value)));
            var time=response.putObject("time");time.put("duration",report.time().duration().toString());time.put("trackedActivityTime",report.time().trackedActivityTime().toString());
            time.put("allocatedTime",report.time().allocatedTime().toString());time.put("unallocatedTime",report.time().unallocatedTime().toString());
            var outside=time.putArray("outsideSession");report.time().outsideSession().stream().map(ActivityId::value).sorted().forEach(value -> outside.add(value.toString()));
            response.put("generatedAt",store.now().toString());response.put("revision",store.revision());return json(response);
        });
    }
    @GET @Path("/me") public Response profile() { ContractInputs.queries(uri.getQueryParameters());return tx(() -> ApiReplies.resource(200,store.profile(),null)); }
    @PUT @Path("/me") public Response profile(@HeaderParam("If-Match") String match,JsonNode body) {
        var input=inputs.validate("ProfileInput",body);String zone=ContractInputs.zone(input.get("zoneId").asText()).getId();ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> { ApiReplies.match(store.profile(),match);return ApiReplies.resource(200,store.updateProfile(zone),null); });
    }
    @GET @Path("/{collection}") public Response list(@PathParam("collection") String collection) {
        String type=ContractInputs.type(collection);var query=uri.getQueryParameters();
        if(type.equals("ACTIVITY")) ContractInputs.queries(query,"from","to","taskId","projectId","labelId","limit","cursor");
        else if(type.equals("TASK")) ContractInputs.queries(query,"q","projectId","parentTaskId","unassigned","limit","cursor");
        else if(Set.of("PROJECT","WORK_SESSION").contains(type)) ContractInputs.queries(query,"q","clientId","limit","cursor");
        else ContractInputs.queries(query,"q","limit","cursor");
        int limit=ContractInputs.number(query.getFirst("limit"),100,1,500);String q=Optional.ofNullable(query.getFirst("q")).orElse("").trim().toLowerCase(Locale.ROOT);if(q.codePointCount(0,q.length())>200) throw ApiFailure.validation();
        return tx(() -> {
            validateFilters(query);List<JsonNode> values;
            if(type.equals("ACTIVITY")) values=activities(query).stream().map(value -> (JsonNode)snapshot(store.owner(),value)).toList();
            else values=store.list(type).stream().filter(value -> {
                if(!value.get(type.equals("WORK_SESSION") ? "title" : type.equals("TASK") ? "summary" : "name").asText().toLowerCase(Locale.ROOT).contains(q)) return false;
                if(query.containsKey("clientId") && !value.path("clientId").asText().equals(ContractInputs.pathId(query.getFirst("clientId")).toString())) return false;
                if(type.equals("TASK")) {
                    if(query.containsKey("projectId") && !value.get("projectId").asText().equals(ContractInputs.pathId(query.getFirst("projectId")).toString())) return false;
                    if(query.containsKey("parentTaskId") && !value.get("parentTaskId").asText().equals(ContractInputs.pathId(query.getFirst("parentTaskId")).toString())) return false;
                    if(query.containsKey("unassigned")) {String flag=query.getFirst("unassigned");if(!Set.of("true","false").contains(flag)) throw ApiFailure.badRequest();if(flag.equals("true") && !value.get("projectId").isNull()) return false;}
                }return true;
            }).sorted(Comparator.comparing(value -> value.get("id").asText())).toList();
            ObjectNode filters=object();query.forEach((name,entries) -> {
                if(Set.of("limit","cursor").contains(name) || (name.equals("q") && q.isEmpty()) || (name.equals("unassigned") && entries.getFirst().equals("false"))) return;
                String value=entries.getFirst();
                if(name.endsWith("Id")) value=ContractInputs.pathId(value).toString();
                if(name.equals("from") || name.equals("to")) value=ContractInputs.instant(value).toString();
                filters.put(name,name.equals("q") ? q : value);
            });
            return json(pages.page(type,values,canonical(filters),limit,query.getFirst("cursor")));
        });
    }
    private void validateFilters(MultivaluedMap<String,String> query) {
        for(var entry:Map.of("clientId","CLIENT","taskId","TASK","projectId","PROJECT","parentTaskId","TASK","labelId","LABEL").entrySet()) if(query.containsKey(entry.getKey())) required(entry.getValue(),ContractInputs.pathId(query.getFirst(entry.getKey())));
        if("true".equals(query.getFirst("unassigned")) && query.containsKey("projectId")) throw ApiFailure.validation();
        if(query.containsKey("unassigned") && !Set.of("true","false").contains(query.getFirst("unassigned"))) throw ApiFailure.badRequest();
    }
    private List<Activity> activities(MultivaluedMap<String,String> query) {
        validateFilters(query);Instant from=ContractInputs.instant(query.getFirst("from")),to=ContractInputs.instant(query.getFirst("to"));if(!to.isAfter(from)) throw ApiFailure.validation();
        var task=Optional.ofNullable(query.getFirst("taskId")).map(ContractInputs::pathId).map(TaskId::new);
        var project=Optional.ofNullable(query.getFirst("projectId")).map(ContractInputs::pathId).map(ProjectId::new);
        var report=store.service().activities(new ActivityQuery(from,to,Optional.of(new OwnerId(store.owner())),task,project,ZoneId.of("UTC")));
        return report.activities().stream().filter(value -> !query.containsKey("labelId") || value.labelIds().contains(new LabelId(ContractInputs.pathId(query.getFirst("labelId")))))
                .sorted(Comparator.comparing(Activity::start).thenComparing(value -> value.id().value().toString())).toList();
    }
    @GET @Path("/reports/activities") public Response report() {
        var query=uri.getQueryParameters();ContractInputs.queries(query,"from","to","taskId","projectId","labelId","zoneId");
        return tx(() -> {
            List<Activity> values=activities(query);Instant from=ContractInputs.instant(query.getFirst("from")),to=ContractInputs.instant(query.getFirst("to")),asOf=store.now();
            ZoneId zone=ContractInputs.zone(Optional.ofNullable(query.getFirst("zoneId")).orElse(store.profile().get("zoneId").asText()));
            ObjectNode result=object();result.put("from",from.toString());result.put("to",to.toString());result.put("zoneId",zone.getId());result.put("generatedAt",asOf.toString());result.put("revision",store.revision());
            Duration total=values.stream().map(value -> value.durationBetween(from,to,asOf)).reduce(Duration.ZERO,Duration::plus);result.put("total",total.toString());var days=result.putArray("days");
            // Iterate only dates touched by recorded intervals, not empty centuries of a query.
            SortedMap<LocalDate,SortedMap<String,Duration>> sums=new TreeMap<>();
            for(Activity activity:values) {
                Instant start=activity.start().isAfter(from) ? activity.start() : from,end=activity.end().orElse(asOf).isBefore(to) ? activity.end().orElse(asOf) : to;
                if(!end.isAfter(start)) continue;
                LocalDate date=start.atZone(zone).toLocalDate(),last=end.minusNanos(1).atZone(zone).toLocalDate();
                for(;!date.isAfter(last);date=date.plusDays(1)) {
                    Instant dayStart=date.atStartOfDay(zone).toInstant(),dayEnd=date.plusDays(1).atStartOfDay(zone).toInstant();
                    Instant clippedStart=start.isAfter(dayStart) ? start : dayStart,clippedEnd=end.isBefore(dayEnd) ? end : dayEnd;
                    Duration duration=Duration.between(clippedStart,clippedEnd);sums.computeIfAbsent(date,ignored -> new TreeMap<>()).merge(activity.taskId()==null ? "" : activity.taskId().value().toString(),duration,Duration::plus);
                }
            }
            sums.forEach((date,tasks) -> {var day=object();day.put("date",date.toString());day.put("total",tasks.values().stream().reduce(Duration.ZERO,Duration::plus).toString());var taskTotals=day.putArray("tasks");tasks.forEach((id,duration) -> {var row=object();if(id.isEmpty()) row.putNull("taskId");else row.put("taskId",id);row.put("total",duration.toString());taskTotals.add(row);});days.add(day);});
            return json(result);
        });
    }
}
