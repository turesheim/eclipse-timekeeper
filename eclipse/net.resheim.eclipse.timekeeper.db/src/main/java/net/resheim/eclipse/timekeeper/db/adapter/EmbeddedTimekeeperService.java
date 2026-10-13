package net.resheim.eclipse.timekeeper.db.adapter;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** OSGi deployment of the embedded JPA-backed application service. */
public final class EmbeddedTimekeeperService extends DefaultTimekeeperService implements net.resheim.eclipse.timekeeper.service.WorkSessionService {
    private final net.resheim.eclipse.timekeeper.service.WorkSessionService sessions;
	private java.util.concurrent.ScheduledExecutorService maintenance;
	private String partitionDate;
	public EmbeddedTimekeeperService() {
		this(new JpaServicePorts(() -> TimekeeperPlugin.getDefault().getEntityManager()));
		maintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
			var thread=new Thread(work,"Timekeeper day boundaries");thread.setDaemon(true);return thread;
		});
		maintenance.scheduleWithFixedDelay(this::maintainDays,1,1,java.util.concurrent.TimeUnit.SECONDS);
	}

	EmbeddedTimekeeperService(JpaServicePorts ports) {
        this(ports,new net.resheim.eclipse.timekeeper.service.DefaultWorkSessionService(ports.workSessionPorts(
                event -> ports.afterCommit(() -> TimekeeperPlugin.getDefault().notifyServiceListeners()))));
    }
    private EmbeddedTimekeeperService(JpaServicePorts ports,net.resheim.eclipse.timekeeper.service.WorkSessionService sessions) {
        super(ports.ports(event -> ports.afterCommit(() -> TimekeeperPlugin.getDefault().notifyServiceListeners())),sessions);
        this.sessions=sessions;
	}
    public net.resheim.eclipse.timekeeper.domain.WorkSession startWorkSession(net.resheim.eclipse.timekeeper.service.Commands.StartWorkSession command) {return sessions.startWorkSession(command);}
    public net.resheim.eclipse.timekeeper.domain.WorkSession stopWorkSession(net.resheim.eclipse.timekeeper.service.Commands.StopWorkSession command) {return sessions.stopWorkSession(command);}
    public net.resheim.eclipse.timekeeper.domain.WorkSession createWorkSession(net.resheim.eclipse.timekeeper.service.Commands.CreateWorkSession command) {return sessions.createWorkSession(command);}
    public net.resheim.eclipse.timekeeper.domain.WorkSession updateWorkSession(net.resheim.eclipse.timekeeper.service.Commands.UpdateWorkSession command) {return sessions.updateWorkSession(command);}
    public void deleteWorkSession(net.resheim.eclipse.timekeeper.service.Commands.DeleteWorkSession command) {sessions.deleteWorkSession(command);}
    public java.util.Optional<net.resheim.eclipse.timekeeper.domain.WorkSession> workSession(net.resheim.eclipse.timekeeper.domain.WorkSessionId id) {return sessions.workSession(id);}
    public java.util.Optional<net.resheim.eclipse.timekeeper.domain.WorkSession> activeWorkSession(net.resheim.eclipse.timekeeper.domain.OwnerId owner) {return sessions.activeWorkSession(owner);}
    public java.util.List<net.resheim.eclipse.timekeeper.domain.WorkSession> workSessions(net.resheim.eclipse.timekeeper.domain.OwnerId owner) {return sessions.workSessions(owner);}
    public Association associateWorkSession(net.resheim.eclipse.timekeeper.service.Commands.AssociateWorkSession command) {return sessions.associateWorkSession(command);}
    public Report workSessionReport(net.resheim.eclipse.timekeeper.domain.WorkSessionId id) {return sessions.workSessionReport(id);}

	public void stopMaintenance() {if(maintenance!=null) maintenance.shutdownNow();}
	private void maintainDays() {
		var plugin=TimekeeperPlugin.getDefault();
		if(plugin==null || !plugin.isReady()) return;
		var zone=java.time.ZoneId.systemDefault();
		String date=plugin.databaseIdentity()+":"+plugin.importGeneration()+":"+zone+":"+java.time.LocalDate.now(zone);
		if(date.equals(partitionDate)) return;
		try {rolloverActivities(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL);partitionDate=date;}
		catch(RuntimeException failure) {
			plugin.getLog().log(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.WARNING,
					TimekeeperPlugin.BUNDLE_ID,"Could not maintain daily activity boundaries",failure));
		}
	}
}
