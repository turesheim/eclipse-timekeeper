package net.resheim.eclipse.timekeeper.service;

import java.util.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Independent session controls and optional activity associations. */
public interface WorkSessionService {
    record Association(Activity activity, boolean outsideSession) { }
    record Report(WorkSession session, List<Activity> activities, WorkSessionTime time) {
        public Report { activities = List.copyOf(activities); }
    }
    WorkSession startWorkSession(StartWorkSession command);
    WorkSession stopWorkSession(StopWorkSession command);
    WorkSession createWorkSession(CreateWorkSession command);
    WorkSession updateWorkSession(UpdateWorkSession command);
    void deleteWorkSession(DeleteWorkSession command);
    Optional<WorkSession> workSession(WorkSessionId id);
    Optional<WorkSession> activeWorkSession(OwnerId ownerId);
    List<WorkSession> workSessions(OwnerId ownerId);
    Association associateWorkSession(AssociateWorkSession command);
    Report workSessionReport(WorkSessionId id);
}
