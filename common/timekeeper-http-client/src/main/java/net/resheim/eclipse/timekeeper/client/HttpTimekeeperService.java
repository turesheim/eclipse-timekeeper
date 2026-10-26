package net.resheim.eclipse.timekeeper.client;

import static net.resheim.eclipse.timekeeper.client.WireValues.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.*;

/** Synchronous HTTP port for worker threads. UI adapters must dispatch before calling. */
public final class HttpTimekeeperService implements TimekeeperService,WorkSessionService,AutoCloseable {
    @Override public void rolloverActivities(OwnerId owner) {activeActivity(owner);}
    public record Profile(OwnerId owner,ZoneId zone,long version) { }
    public record ChangeBatch(long nextSequence,boolean changed,boolean resynchronize) { }
    public record Metadata(Profile profile,List<Project> projects,List<Task> tasks,List<Label> labels,Optional<Activity> active,long revision) { }
    private record Result(int status,JsonElement body,String etag) { }
    private record VersionTag(long version,String tag) { }
    private record Page(List<JsonObject> items,long revision) { }
    private final URI base;
    private final Supplier<String> token;
    private final Duration timeout;
    private final HttpClient http;
    private final ConcurrentMap<String,VersionTag> tags=new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<OwnerId> boundOwner=new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Profile profile;
    public HttpTimekeeperService(URI server,Supplier<String> token,Duration timeout) {
        base=baseUri(server);this.token=Objects.requireNonNull(token);this.timeout=Objects.requireNonNull(timeout);
        if(timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Use a positive server timeout");
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public URI endpoint() {return base;}
    public static URI baseUri(URI input) {
        if(input==null || input.getHost()==null || input.getUserInfo()!=null || input.getRawQuery()!=null || input.getFragment()!=null || !Set.of("http","https").contains(input.getScheme()))
            throw new IllegalArgumentException("Use a server URL without credentials, query or fragment");
        if(input.getScheme().equals("http") && !Set.of("localhost","127.0.0.1","[::1]","::1").contains(input.getHost()))
            throw new IllegalArgumentException("Use HTTPS for a server outside this computer");
        String value=input.toASCIIString().replaceAll("/+$","");if(!value.endsWith("/api/v1")) value+="/api/v1";return URI.create(value);
    }
    private Result request(String method,String path,JsonElement body,Map<String,String> headers) {
        String credential=token.get();if(credential==null || credential.isBlank()) throw new RemoteFailure(RemoteFailure.Kind.AUTHENTICATION,"UNAUTHENTICATED");
        credential=credential.strip();
        if(credential.length()>4096 || !credential.matches("[A-Za-z0-9._~+/-]+=*")) throw new RemoteFailure(RemoteFailure.Kind.AUTHENTICATION,"INVALID_TOKEN");
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(timeout).header("Authorization","Bearer "+credential).header("Accept","application/json, application/problem+json");
        var expectedOwner=boundOwner.get();if(expectedOwner!=null) builder.header("X-Timekeeper-Owner",Base64.getUrlEncoder().withoutPadding().encodeToString(expectedOwner.value().getBytes(StandardCharsets.UTF_8)));
        headers.forEach(builder::header);if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
        var future=http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response=future.get();
            JsonElement value=JsonNull.INSTANCE;
            try {if(!response.body().isBlank()) value=JsonParser.parseString(response.body());}
            catch(JsonParseException invalid) {if(response.statusCode()<300) throw invalid;}
            if(response.statusCode()>=300) {
                String code=value.isJsonObject() && value.getAsJsonObject().has("code") ? value.getAsJsonObject().get("code").getAsString() : "UNAVAILABLE";
                throw failure(response.statusCode(),code);
            }
            response.headers().firstValue("X-Timekeeper-Owner").ifPresent(header->bindOwner(decodeOwner(header)));
            if(value.isJsonObject() && value.getAsJsonObject().has("ownerId")) {
                var owner=value.getAsJsonObject().get("ownerId");
                if(!owner.isJsonPrimitive() || !owner.getAsJsonPrimitive().isString()) throw new IllegalStateException();
                bindOwner(new OwnerId(owner.getAsString()));
            }
            return new Result(response.statusCode(),value,response.headers().firstValue("ETag").orElse(null));
        } catch(InterruptedException interrupted) {future.cancel(true);Thread.currentThread().interrupt();throw new RemoteFailure(RemoteFailure.Kind.CANCELLED,"CANCELLED");}
        catch(CancellationException error) {throw new RemoteFailure(RemoteFailure.Kind.CANCELLED,"CANCELLED");}
        catch(ExecutionException error) {
            if(error.getCause() instanceof HttpTimeoutException) throw new RemoteFailure(RemoteFailure.Kind.TIMEOUT,"TIMEOUT");
            throw new RemoteFailure(RemoteFailure.Kind.OFFLINE,error.getCause() instanceof javax.net.ssl.SSLException ? "TLS_ERROR" : "OFFLINE");
        }
        catch(JsonParseException | IllegalStateException error) {throw new RemoteFailure(RemoteFailure.Kind.UNAVAILABLE,"INVALID_RESPONSE");}
    }
    private void bindOwner(OwnerId owner) {
        boundOwner.compareAndSet(null,owner);
        if(!owner.equals(boundOwner.get())) throw new RemoteFailure(RemoteFailure.Kind.AUTHENTICATION,"OWNER_CHANGED");
    }
    private static OwnerId decodeOwner(String header) {
        try {
            if(header.length()>1100 || !header.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String owner=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(header))).toString();
            if(owner.isBlank() || !owner.equals(owner.trim()) || owner.codePointCount(0,owner.length())>200
                    || !header.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(owner.getBytes(StandardCharsets.UTF_8)))) throw new IllegalArgumentException();
            return new OwnerId(owner);
        } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {throw new RemoteFailure(RemoteFailure.Kind.UNAVAILABLE,"INVALID_RESPONSE");}
    }
    private static RemoteFailure failure(int status,String code) {
        return new RemoteFailure(switch(status) {case 401->RemoteFailure.Kind.AUTHENTICATION;case 403->RemoteFailure.Kind.FORBIDDEN;case 404->RemoteFailure.Kind.NOT_FOUND;case 409,412->RemoteFailure.Kind.CONFLICT;case 400,413,422,428->RemoteFailure.Kind.VALIDATION;default->RemoteFailure.Kind.UNAVAILABLE;},code,status);
    }
    private Result request(String method,String path,JsonElement body) {return request(method,path,body,Map.of());}
    private JsonObject remember(String path,Result result) {
        JsonObject value=result.body().getAsJsonObject();
        if(result.etag()!=null && value.has("version")) tags.compute(path,(key,old)-> old!=null && old.version()>value.get("version").getAsLong() ? old : new VersionTag(value.get("version").getAsLong(),result.etag()));
        return value;
    }
    private Optional<JsonObject> get(String path) {
        try {Result result=request("GET",path,null);if(result.status()==204) return Optional.empty();return Optional.of(remember(path,result));}
        catch(RemoteFailure e) {if(e.kind()==RemoteFailure.Kind.NOT_FOUND) return Optional.empty();throw e;}
    }
    private String match(String path,long expected) {
        VersionTag known=tags.get(path);
        if(known==null || known.version()!=expected) {
            JsonObject value=get(path).orElseThrow(() -> new RemoteFailure(RemoteFailure.Kind.NOT_FOUND,"NOT_FOUND"));
            if(value.get("version").getAsLong()!=expected) throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"STALE_VERSION");known=tags.get(path);
        }
        if(known==null) throw new RemoteFailure(RemoteFailure.Kind.UNAVAILABLE,"MISSING_ETAG");return known.tag();
    }
    private JsonObject create(String collection,JsonObject body) {Result result=request("POST","/"+collection,body);var value=result.body().getAsJsonObject();return remember("/"+collection+"/"+value.get("id").getAsString(),result);}
    private JsonObject put(String path,long version,JsonObject body) {return remember(path,request("PUT",path,body,Map.of("If-Match",match(path,version))));}
    private void delete(String path,long version) {request("DELETE",path,null,Map.of("If-Match",match(path,version)));tags.remove(path);}
    private static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String query(Map<String,String> values) {return values.entrySet().stream().map(entry -> encode(entry.getKey())+"="+encode(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    private Page page(String collection,Map<String,String> filters) {
        for(int attempt=0;attempt<3;attempt++) {
            try {
                List<JsonObject> values=new ArrayList<>();String cursor=null;long revision=-1;
                do {
                    var args=new LinkedHashMap<>(filters);args.put("limit","500");if(cursor!=null) args.put("cursor",cursor);
                    JsonObject response=request("GET","/"+collection+"?"+query(args),null).body().getAsJsonObject();
                    long current=response.get("revision").getAsLong();if(revision!=-1 && revision!=current) throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"CURSOR_EXPIRED");revision=current;
                    response.getAsJsonArray("items").forEach(value -> values.add(value.getAsJsonObject()));cursor=text(response.get("nextCursor")).orElse(null);
                } while(cursor!=null);
                return new Page(List.copyOf(values),revision);
            } catch(RemoteFailure error) {if(!error.code().equals("CURSOR_EXPIRED") || attempt==2) throw error;}
        }throw new IllegalStateException();
    }
    public Profile profile() {
        JsonObject value=remember("/me",request("GET","/me",null));profile=new Profile(new OwnerId(value.get("ownerId").getAsString()),ZoneId.of(value.get("zoneId").getAsString()),value.get("version").getAsLong());return profile;
    }
    public Metadata metadata() {
        for(int attempt=0;attempt<3;attempt++) {
            Profile profile=profile();Page projects=page("projects",Map.of()),tasks=page("tasks",Map.of()),labels=page("labels",Map.of());var active=activeActivity(OwnerId.LOCAL);
            Profile confirmed=profile();long end=page("projects",Map.of()).revision();
            if(profile.owner().equals(confirmed.owner()) && profile.version()==confirmed.version() && projects.revision()==tasks.revision() && tasks.revision()==labels.revision() && end==labels.revision())
                return new Metadata(profile,projects.items().stream().map(WireValues::project).toList(),tasks.items().stream().map(WireValues::task).toList(),labels.items().stream().map(WireValues::label).toList(),active,end);
        }throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"CURSOR_EXPIRED");
    }
    private OwnerId owner(OwnerId requested) {
        Profile value=profile;if(value==null) value=profile();if(requested!=null && !requested.equals(OwnerId.LOCAL) && !requested.equals(value.owner())) throw new RemoteFailure(RemoteFailure.Kind.FORBIDDEN,"OWNER_SCOPE");return value.owner();
    }
    @Override public Project createProject(CreateProject command) {var body=object();body.addProperty("name",command.name());return WireValues.project(create("projects",body));}
    @Override public Project updateProject(UpdateProject command) {var body=object();body.addProperty("name",command.name());return WireValues.project(put("/projects/"+command.id().value(),command.expectedVersion(),body));}
    @Override public void deleteProject(DeleteProject command) {delete("/projects/"+command.id().value(),command.expectedVersion());}
    @Override public Optional<Project> project(ProjectId id) {return get("/projects/"+id.value()).map(WireValues::project);}
    @Override public List<Project> projects() {return page("projects",Map.of()).items().stream().map(WireValues::project).toList();}
    private static JsonObject taskBody(Optional<ProjectId> project,Optional<TaskId> parent,String summary,Optional<String> url) {var body=object();optional(body,"projectId",project.map(ProjectId::value));optional(body,"parentTaskId",parent.map(TaskId::value));body.addProperty("summary",summary);optional(body,"url",url);return body;}
    private static void planning(JsonObject body,TaskPlanning value) {body.addProperty("status",value.status().name());optional(body,"scheduledDate",value.scheduledDate());optional(body,"completedAt",value.completedAt());}
    @Override public Task createTask(CreateTask command) {var body=taskBody(command.projectId(),command.parentTaskId(),command.summary(),command.url());if(!command.planning().equals(TaskPlanning.unplanned()))planning(body,command.planning());if(!command.checklist().equals(TaskChecklist.empty()))body.add("checklistItems",WireValues.checklist(command.checklist()));if(!command.timing().equals(TaskTiming.empty()))WireValues.timing(body,command.timing());var refs=new JsonArray();command.externalReferences().forEach(ref -> refs.add(reference(ref)));body.add("externalReferences",refs);return WireValues.task(create("tasks",body));}
    @Override public Task updateTask(UpdateTask command) {return WireValues.task(put("/tasks/"+command.id().value(),command.expectedVersion(),taskBody(command.projectId(),command.parentTaskId(),command.summary(),command.url())));}
    @Override public Task updateTaskPlanning(UpdateTaskPlanning command) {var body=object();planning(body,command.planning());return putTaskSection(command.id(),"planning",command.expectedVersion(),body);}
    @Override public Task updateTaskChecklist(UpdateTaskChecklist command) {var body=object();body.add("items",WireValues.checklist(command.checklist()));return putTaskSection(command.id(),"checklist",command.expectedVersion(),body);}
    @Override public Task updateTaskTiming(UpdateTaskTiming command) {var body=object();WireValues.timing(body,command.timing());return putTaskSection(command.id(),"timing",command.expectedVersion(),body);}
    private Task putTaskSection(TaskId id,String section,long version,JsonObject body) {String path="/tasks/"+id.value();return WireValues.task(remember(path,request("PUT",path+"/"+section,body,Map.of("If-Match",match(path,version)))));}
    @Override public void deleteTask(DeleteTask command) {delete("/tasks/"+command.id().value(),command.expectedVersion());}
    @Override public Optional<Task> task(TaskId id) {return get("/tasks/"+id.value()).map(WireValues::task);}
    @Override public List<Task> tasks() {return page("tasks",Map.of()).items().stream().map(WireValues::task).toList();}
    @Override public Task linkExternalReference(LinkExternalReference command) {String path="/tasks/"+command.taskId().value();String tag=match(path,command.expectedVersion());return WireValues.task(remember(path,request("PUT",path+"/external-references",reference(command.reference()),Map.of("If-Match",tag))));}
    @Override public Task unlinkExternalReference(UnlinkExternalReference command) {String path="/tasks/"+command.taskId().value();String filter=query(Map.of("providerId",command.reference().providerId(),"repositoryId",command.reference().repositoryId(),"externalId",command.reference().externalId()));return WireValues.task(remember(path,request("DELETE",path+"/external-references?"+filter,null,Map.of("If-Match",match(path,command.expectedVersion())))));}
    @Override public Optional<Task> findTask(ExternalTaskReferenceKey reference) {return get("/tasks/by-external-reference?"+query(Map.of("providerId",reference.providerId(),"repositoryId",reference.repositoryId(),"externalId",reference.externalId()))).map(WireValues::task);}
    private static JsonObject labelBody(String name,Optional<String> color) {var body=object();body.addProperty("name",name);optional(body,"color",color.map(WireValues::hex));return body;}
    @Override public Label createLabel(CreateLabel command) {return WireValues.label(create("labels",labelBody(command.name(),command.color())));}
    @Override public Label updateLabel(UpdateLabel command) {return WireValues.label(put("/labels/"+command.id().value(),command.expectedVersion(),labelBody(command.name(),command.color())));}
    @Override public void deleteLabel(DeleteLabel command) {delete("/labels/"+command.id().value(),command.expectedVersion());}
    @Override public Optional<Label> label(LabelId id) {return get("/labels/"+id.value()).map(WireValues::label);}
    @Override public List<Label> labels() {return page("labels",Map.of()).items().stream().map(WireValues::label).toList();}
    @Override public Optional<Activity> activeActivity(OwnerId requested) {owner(requested);return get("/activities/active").map(value -> {tags.put("/activities/"+value.get("id").getAsString(),tags.get("/activities/active"));return WireValues.activity(value);});}
    @Override public Activity startActivity(StartActivity command) {owner(command.ownerId());return activate(command.taskId(),command.summary(),command.labelIds(),activeActivity(OwnerId.LOCAL),command.workSessionId(),command.projectId());}
    /** Compare the activity actually observed by the caller; never silently overwrite a successor. */
    public Activity activate(TaskId task,String summary,Set<LabelId> labels,Optional<Activity> observed) {
        return activate(task,summary,labels,observed,Optional.empty(),Optional.empty());
    }
    public Activity activate(TaskId task,String summary,Set<LabelId> labels,Optional<Activity> observed,Optional<WorkSessionId> session,Optional<ProjectId> project) {
        var body=object();optional(body,"taskId",Optional.ofNullable(task).map(TaskId::value));
        session.ifPresent(id -> body.addProperty("workSessionId",id.value().toString()));project.ifPresent(id -> body.addProperty("projectId",id.value().toString()));body.addProperty("summary",summary);body.add("labelIds",WireValues.labels(labels));optional(body,"expectedActiveId",observed.map(value -> value.id().value()));
        if(observed.isEmpty()) body.add("expectedActiveVersion",JsonNull.INSTANCE);else body.addProperty("expectedActiveVersion",observed.get().version());
        Result result=request("POST","/activities/start",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));var value=result.body().getAsJsonObject();return WireValues.activity(remember("/activities/"+value.get("id").getAsString(),result));
    }
    @Override public Activity stopActivity(StopActivity command) {String path="/activities/"+command.id().value();return WireValues.activity(remember(path,request("POST",path+"/stop",null,Map.of("If-Match",match(path,command.expectedVersion()),"Idempotency-Key",UUID.randomUUID().toString()))));}
    private static JsonObject interval(Instant start,Optional<Instant> end,String summary,Set<LabelId> labels) {var body=object();body.addProperty("start",start.toString());optional(body,"end",end);body.addProperty("summary",summary);body.add("labelIds",WireValues.labels(labels));return body;}
    @Override public Activity createActivity(CreateActivity command) {owner(command.ownerId());var body=interval(command.start(),command.end(),command.summary(),command.labelIds());optional(body,"taskId",Optional.ofNullable(command.taskId()).map(TaskId::value));command.workSessionId().ifPresent(id -> body.addProperty("workSessionId",id.value().toString()));command.projectId().ifPresent(id -> body.addProperty("projectId",id.value().toString()));return WireValues.activity(create("activities",body));}
    @Override public Activity updateActivity(UpdateActivity command) {return WireValues.activity(put("/activities/"+command.id().value(),command.expectedVersion(),interval(command.start(),command.end(),command.summary(),command.labelIds())));}
    @Override public void deleteActivity(DeleteActivity command) {delete("/activities/"+command.id().value(),command.expectedVersion());}
    @Override public Optional<Activity> activity(ActivityId id) {return get("/activities/"+id.value()).map(WireValues::activity);}
    @Override public ActivityReport activities(ActivityQuery input) {
        input.ownerId().ifPresent(this::owner);var filters=new LinkedHashMap<String,String>();filters.put("from",input.fromInclusive().toString());filters.put("to",input.toExclusive().toString());input.taskId().ifPresent(id -> filters.put("taskId",id.value().toString()));input.projectId().ifPresent(id -> filters.put("projectId",id.value().toString()));
        for(int attempt=0;attempt<3;attempt++) {
            Page page=page("activities",filters);var reportFilters=new LinkedHashMap<>(filters);reportFilters.put("zoneId",input.zoneId().getId());
            JsonObject report=request("GET","/reports/activities?"+query(reportFilters),null).body().getAsJsonObject();
            if(page.revision()==report.get("revision").getAsLong()) return new ActivityReport(input,Instant.parse(report.get("generatedAt").getAsString()),page.items().stream().map(WireValues::activity).toList(),Duration.parse(report.get("total").getAsString()));
        }throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"CURSOR_EXPIRED");
    }
    /** Full owned activity choices without constructing a multi-century daily report. */
    public List<Activity> allActivities() {
        return page("activities",Map.of("from","0000-01-01T00:00:00Z","to","9999-12-31T23:59:59.999999999Z")).items().stream().map(WireValues::activity).toList();
    }
    private static JsonObject workSessionInterval(Instant start,Optional<Instant> end,String summary) {
        var body=object();body.addProperty("start",start.toString());optional(body,"end",end);body.addProperty("summary",summary);return body;
    }
    @Override public WorkSession createWorkSession(CreateWorkSession command) {
        owner(command.ownerId());return WireValues.workSession(create("work-sessions",workSessionInterval(command.start(),command.end(),command.summary())));
    }
    @Override public WorkSession updateWorkSession(UpdateWorkSession command) {
        return WireValues.workSession(put("/work-sessions/"+command.id().value(),command.expectedVersion(),workSessionInterval(command.start(),command.end(),command.summary())));
    }
    @Override public void deleteWorkSession(DeleteWorkSession command) {delete("/work-sessions/"+command.id().value(),command.expectedVersion());}
    @Override public Optional<WorkSession> workSession(WorkSessionId id) {return get("/work-sessions/"+id.value()).map(WireValues::workSession);}
    @Override public List<WorkSession> workSessions(OwnerId requested) {owner(requested);return page("work-sessions",Map.of()).items().stream().map(WireValues::workSession).toList();}
    @Override public Optional<WorkSession> activeWorkSession(OwnerId requested) {
        owner(requested);return get("/work-sessions/active").map(value -> {
            tags.put("/work-sessions/"+value.get("id").getAsString(),tags.get("/work-sessions/active"));return WireValues.workSession(value);
        });
    }
    @Override public WorkSession startWorkSession(StartWorkSession command) {return startWorkSession(command,UUID.randomUUID());}
    public WorkSession startWorkSession(StartWorkSession command,UUID commandId) {
        owner(command.ownerId());var body=object();body.addProperty("summary",command.summary());
        var result=request("POST","/work-sessions/start",body,Map.of("Idempotency-Key",commandId.toString()));var value=result.body().getAsJsonObject();
        return WireValues.workSession(remember("/work-sessions/"+value.get("id").getAsString(),result));
    }
    @Override public WorkSession stopWorkSession(StopWorkSession command) {return stopWorkSession(command,UUID.randomUUID());}
    public WorkSession stopWorkSession(StopWorkSession command,UUID commandId) {
        return prepareWorkSessionStop(command,commandId).get();
    }
    /** Capture before the first write; retain this operation to retry an unknown outcome. */
    public Supplier<WorkSession> prepareWorkSessionStop(StopWorkSession command,UUID commandId) {
        String path="/work-sessions/"+command.id().value();
        var headers=Map.of("If-Match",match(path,command.expectedVersion()),"Idempotency-Key",commandId.toString());
        return ()->WireValues.workSession(remember(path,request("POST",path+"/stop",null,headers)));
    }
    @Override public Association associateWorkSession(AssociateWorkSession command) {
        String path="/activities/"+command.activityId().value();var body=object();optional(body,"workSessionId",command.workSessionId().map(WorkSessionId::value));
        var result=request("PUT",path+"/work-session",body,Map.of("If-Match",match(path,command.expectedActivityVersion())));
        var response=result.body().getAsJsonObject();var activity=response.getAsJsonObject("activity");
        return new Association(WireValues.activity(remember(path,new Result(result.status(),activity,result.etag()))),response.get("outsideSession").getAsBoolean());
    }
    @Override public WorkSessionService.Report workSessionReport(WorkSessionId id) {
        var value=request("GET","/work-sessions/"+id.value()+"/report",null).body().getAsJsonObject();
        var session=WireValues.workSession(value.getAsJsonObject("session"));var activities=new ArrayList<Activity>();
        value.getAsJsonArray("activities").forEach(activity -> activities.add(WireValues.activity(activity.getAsJsonObject())));
        var time=value.getAsJsonObject("time");var outside=new HashSet<ActivityId>();
        time.getAsJsonArray("outsideSession").forEach(activity -> outside.add(new ActivityId(WireValues.id(activity))));
        return new WorkSessionService.Report(session,activities,new WorkSessionTime(Duration.parse(time.get("duration").getAsString()),
                Duration.parse(time.get("trackedActivityTime").getAsString()),Duration.parse(time.get("allocatedTime").getAsString()),
                Duration.parse(time.get("unallocatedTime").getAsString()),Set.copyOf(outside)));
    }
    public JsonObject exportData() {return request("GET","/data/export",null).body().getAsJsonObject();}
    public JsonObject importData(JsonObject data) {return request("POST","/data/import",data,Map.of("Idempotency-Key",UUID.randomUUID().toString())).body().getAsJsonObject();}
    public int exportCsv(java.nio.file.Path folder) throws IOException {return CsvSnapshot.write(exportData(),folder);}
    /** The same unresolved payload, backend and owner reuse their command key across restarts. */
    public int importCsv(java.nio.file.Path folder,java.nio.file.Path journalFolder) throws IOException {
        JsonObject data=CsvSnapshot.read(folder);
        var journal=new ImportJournal(journalFolder,base.toString(),profile().owner().value(),data.toString());
        String key=journal.pending();
        try {
            JsonObject result=request("POST","/data/import",data,Map.of("Idempotency-Key",key)).body().getAsJsonObject();
            journal.acknowledged(key);return result.getAsJsonArray("ids").size();
        } catch(RemoteFailure failure) {
            if(Set.of(RemoteFailure.Kind.AUTHENTICATION,RemoteFailure.Kind.FORBIDDEN,RemoteFailure.Kind.VALIDATION,RemoteFailure.Kind.CONFLICT,RemoteFailure.Kind.NOT_FOUND).contains(failure.kind())) journal.acknowledged(key);
            throw failure;
        }
    }
    public JsonObject changes(long after,int waitSeconds) {return request("GET","/changes?after="+after+"&waitSeconds="+waitSeconds,null).body().getAsJsonObject();}
    public ChangeBatch changeBatch(long after,int waitSeconds) {
        try {
            var page=changes(after,waitSeconds);var items=page.getAsJsonArray("items");
            long next=page.get("nextSequence").getAsLong(),previous=after;
            boolean more=page.get("hasMore").getAsBoolean();
            for(var item:items) {
                long sequence=item.getAsJsonObject().get("sequence").getAsLong();
                if(sequence<=previous) throw new IllegalStateException("Invalid change order");previous=sequence;
            }
            if(next!=previous || next<after) throw new IllegalStateException("Invalid change cursor");
            return new ChangeBatch(next,!items.isEmpty(),items.isEmpty() && more);
        } catch(RemoteFailure failure) {throw failure;}
        catch(RuntimeException invalid) {throw new RemoteFailure(RemoteFailure.Kind.UNAVAILABLE,"INVALID_CHANGE_PAGE");}
    }
    /** Used only inside serialized full resynchronization after a server cursor reset. */
    public void invalidateVersions() {tags.clear();}
    @Override public void close() {http.shutdownNow();}
}
