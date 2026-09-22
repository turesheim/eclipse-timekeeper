package net.resheim.eclipse.timekeeper.db.adapter;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** OSGi deployment of the embedded JPA-backed application service. */
public final class EmbeddedTimekeeperService extends DefaultTimekeeperService {
	public EmbeddedTimekeeperService() {
		this(new JpaServicePorts(() -> TimekeeperPlugin.getDefault().getEntityManager()));
	}

	EmbeddedTimekeeperService(JpaServicePorts ports) {
		super(ports.ports(event -> TimekeeperPlugin.getDefault().notifyServiceListeners()));
	}
}
