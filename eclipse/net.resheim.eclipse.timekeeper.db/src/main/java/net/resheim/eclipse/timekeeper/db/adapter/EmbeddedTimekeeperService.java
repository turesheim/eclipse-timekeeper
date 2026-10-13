package net.resheim.eclipse.timekeeper.db.adapter;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** OSGi deployment of the embedded JPA-backed application service. */
public final class EmbeddedTimekeeperService extends DefaultTimekeeperService {
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
		super(ports.ports(event -> ports.afterCommit(() -> TimekeeperPlugin.getDefault().notifyServiceListeners())));
	}
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
