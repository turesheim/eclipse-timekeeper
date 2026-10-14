package net.resheim.eclipse.timekeeper.ui.client;

import java.util.Optional;
import org.eclipse.swt.widgets.Display;
import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Captured immutable records remain authoritative when a restored history reuses versions. */
public final class RemoteRecordSnapshots {
    private RemoteRecordSnapshots() { }
    public static void verify(TimekeeperService service,Object original) {
        if(original==null || !TimekeeperUiPlugin.getDefault().isRemote()) return;
        if(Display.getCurrent()!=null) throw new IllegalStateException("Remote record validation requires a background operation");
        Optional<?> current;
        if(original instanceof Task task) current=service.task(task.id());
        else if(original instanceof Project project) current=service.project(project.id());
        else if(original instanceof Activity activity) current=service.activity(activity.id());
        else if(original instanceof Label label) current=service.label(label.id());
        else if(original instanceof net.resheim.eclipse.timekeeper.domain.WorkSession session) current=TimekeeperUiPlugin.getDefault().getWorkSessionService().workSession(session.id());
        else throw new IllegalArgumentException("Unsupported record snapshot");
        if(current.isEmpty()) throw new RemoteFailure(RemoteFailure.Kind.NOT_FOUND,"NOT_FOUND");
        // The serialized operation reads current fields and supplies the ETag
        // for its following conditional write. Numeric equality alone is unsafe.
        if(!original.equals(current.get())) throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"STALE_VERSION");
    }
    public static String message(RuntimeException failure) {
        if(failure instanceof RemoteFailure problem && problem.kind()==RemoteFailure.Kind.CONFLICT
                && problem.code().equals("STALE_VERSION"))
            return "This record has changed. Close and reopen the editor before saving.";
        return failure.getMessage();
    }
}
