package net.resheim.eclipse.timekeeper.mylyn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.tasks.core.ITask;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Separates provider mappings from native projections owned by another backend/account. */
@SuppressWarnings("restriction")
public final class MylynBackendScope {
    public static final String ATTR_PROJECTION_SCOPE="net.resheim.eclipse.timekeeper.projection-scope";
    public static final String ATTR_PROVIDER_SOURCE="net.resheim.eclipse.timekeeper.provider-source";
    private static final String LEGACY_PROJECT_PREFIX="mylyn-project-";
    private static final String LEGACY_SCOPE="legacy-embedded-scope";
    private static volatile MylynBackendScope current;
    private final String identity,scope;
    private final Preferences root,projects,categoryScopes,baselines;
    private final boolean legacyAllowed;

    public MylynBackendScope(String identity,boolean embedded) {
        this.identity=identity;scope=hash(identity);
        root=InstanceScope.INSTANCE.getNode(TimekeeperUiPlugin.PLUGIN_ID).node("mylyn-projects");
        projects=root.node("backends").node(scope).node("projects");baselines=root.node("backends").node(scope).node("baselines");categoryScopes=root.node("category-scopes");
        synchronized(MylynBackendScope.class) {
            if(embedded && root.get(LEGACY_SCOPE,null)==null) {root.put(LEGACY_SCOPE,scope);flush(root);}
        }
        legacyAllowed=embedded && scope.equals(root.get(LEGACY_SCOPE,null));
    }
    /** Do not bind a connecting client to a temporary owner or backend identity. */
    public static MylynBackendScope current() {
        var plugin=TimekeeperUiPlugin.getDefault();
        if(plugin==null || !plugin.snapshotsAvailable()) return null;
        String identity=plugin.backendIdentity();var value=current;
        if(value==null || !value.identity.equals(identity)) {
            synchronized(MylynBackendScope.class) {
                value=current;
                if(value==null || !value.identity.equals(identity)) current=value=new MylynBackendScope(identity,!plugin.isRemote());
            }
        }
        return value;
    }
    public String scope() {return scope;}
    private String taskKey() {return MylynMetadata.ATTR_TIMEKEEPER_TASK_ID+"."+scope;}
    public boolean foreignProjection(ITask task) {
        String origin=task.getAttribute(ATTR_PROJECTION_SCOPE);return origin!=null && !origin.equals(scope);
    }
    public boolean maySynchronize(ITask task) {
        if(foreignProjection(task)) return false;
        return task.getAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID)==null || task.getAttribute(taskKey())!=null || legacyAllowed;
    }
    public boolean ownsProjection(ITask task) {return scope.equals(task.getAttribute(ATTR_PROJECTION_SCOPE));}
    public Optional<TaskId> task(ITask task) {
        if(foreignProjection(task)) return Optional.empty();
        String value=task.getAttribute(taskKey());
        if(value==null && legacyAllowed) value=task.getAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID);
        try {return Optional.ofNullable(value).map(UUID::fromString).map(TaskId::new);}
        catch(IllegalArgumentException malformed) {return Optional.empty();}
    }
    public void remember(ITask task,TaskId id,boolean nativeProjection) {
        if(foreignProjection(task)) throw new IllegalStateException("This Task List projection belongs to another Timekeeper backend. Switch back to that backend.");
        task.setAttribute(taskKey(),id.value().toString());
        task.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID,id.value().toString());
        if(nativeProjection) task.setAttribute(ATTR_PROJECTION_SCOPE,scope);
        else task.setAttribute(ATTR_PROVIDER_SOURCE,"true");
    }
    private String categoryOwner(AbstractTaskCategory category) {
        String handle=category.getHandleIdentifier(),known=categoryScopes.get(hash(handle),null);
        if(known!=null) return known;
        try {
            for(String key:root.keys()) if(key.startsWith(LEGACY_PROJECT_PREFIX) && handle.equals(root.get(key,null)))
                return legacyAllowed ? scope : "legacy";
        } catch(BackingStoreException unavailable) {throw new IllegalStateException("Could not read the Mylyn category mappings.");}
        if(handle.startsWith("timekeeper-project-"+scope+"-")) return scope;
        if(handle.matches("timekeeper-project-[0-9a-f]{64}-.*")) return "foreign";
        if(handle.startsWith("timekeeper-project-")) return legacyAllowed ? scope : "legacy";
        return null;
    }
    public boolean maySynchronize(AbstractTaskCategory category) {String owner=categoryOwner(category);return owner==null || owner.equals(scope);}
    public boolean ownsCategory(AbstractTaskCategory category) {return scope.equals(categoryScopes.get(hash(category.getHandleIdentifier()),null));}
    public Optional<ProjectId> project(AbstractTaskCategory category) {
        if(!maySynchronize(category)) return Optional.empty();
        String handle=category.getHandleIdentifier();
        try {
            for(String key:projects.keys()) if(handle.equals(projects.get(key,null))) return Optional.of(new ProjectId(UUID.fromString(key)));
            if(legacyAllowed) for(String key:root.keys()) if(key.startsWith(LEGACY_PROJECT_PREFIX) && handle.equals(root.get(key,null)))
                return Optional.of(new ProjectId(UUID.fromString(key.substring(LEGACY_PROJECT_PREFIX.length()))));
            if(legacyAllowed && handle.matches("timekeeper-project-[0-9a-fA-F-]{36}"))
                return Optional.of(new ProjectId(UUID.fromString(handle.substring("timekeeper-project-".length()))));
        } catch(BackingStoreException unavailable) {throw new IllegalStateException("Could not read the Mylyn project mappings.");}
        catch(IllegalArgumentException malformed) {return Optional.empty();}
        return Optional.empty();
    }
    public String category(ProjectId project) {return projects.get(project.value().toString(),null);}
    public void remember(ProjectId project,AbstractTaskCategory category) {
        if(!maySynchronize(category)) throw new IllegalStateException("The category belongs to another Timekeeper backend.");
        projects.put(project.value().toString(),category.getHandleIdentifier());
        categoryScopes.put(hash(category.getHandleIdentifier()),scope);flush(root);
    }
    String taskState(Optional<ProjectId> project,Optional<TaskId> parent,String summary,Optional<String> url) {
        return state(project.map(id->id.value().toString()).orElse(null),parent.map(id->id.value().toString()).orElse(null),summary,url.orElse(null));
    }
    boolean taskProjectionUnchanged(ITask task,String proposedState) {
        return proposedState.equals(task.getAttribute("timekeeper.projection-state."+scope));
    }
    long taskProjectionVersion(ITask projection,net.resheim.eclipse.timekeeper.domain.Task current) {
        // Embedded project renames and tracking can advance the JPA version without
        // changing fields editable in Mylyn. Those changes can be safely retained.
        if(taskProjectionUnchanged(projection,taskState(current.projectId(),current.parentTaskId(),current.summary(),current.url())))
            return current.version();
        try {long value=Long.parseLong(projection.getAttribute("timekeeper.projection-version."+scope));return value>=0 ? value : current.version();}
        catch(IllegalArgumentException malformed) {return current.version();}
    }
    void rememberProjection(ITask projection,net.resheim.eclipse.timekeeper.domain.Task task) {
        projection.setAttribute("timekeeper.projection-state."+scope,taskState(task.projectId(),task.parentTaskId(),task.summary(),task.url()));
        projection.setAttribute("timekeeper.projection-version."+scope,String.valueOf(task.version()));
    }
    boolean projectProjectionUnchanged(ProjectId id,String name) {
        return state(name).equals(baselines.get(id.value()+".state",null));
    }
    long projectProjectionVersion(net.resheim.eclipse.timekeeper.domain.Project current) {
        if(projectProjectionUnchanged(current.id(),current.name())) return current.version();
        long value=baselines.getLong(current.id().value()+".version",current.version());return value>=0 ? value : current.version();
    }
    void rememberProjection(net.resheim.eclipse.timekeeper.domain.Project project) {
        String key=project.id().value().toString(),value=state(project.name());
        if(value.equals(baselines.get(key+".state",null)) && project.version()==baselines.getLong(key+".version",-1)) return;
        baselines.put(key+".state",value);baselines.putLong(key+".version",project.version());flush(root);
    }
    private static String state(String... fields) {
        var encoding=java.util.Base64.getUrlEncoder().withoutPadding();
        return hash(java.util.Arrays.stream(fields).map(value->value==null ? "~" : encoding.encodeToString(value.getBytes(StandardCharsets.UTF_8))).collect(java.util.stream.Collectors.joining(".")));
    }
    public void forget(ProjectId project) {projects.remove(project.value().toString());baselines.remove(project.value()+".state");baselines.remove(project.value()+".version");flush(root);}
    public String categoryHandle(ProjectId project) {return "timekeeper-project-"+scope+"-"+project.value();}
    private static void flush(Preferences preferences) {
        try {preferences.flush();}
        catch(BackingStoreException unavailable) {throw new IllegalStateException("Could not store the Mylyn backend mappings.");}
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
