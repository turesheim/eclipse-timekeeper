package net.resheim.eclipse.timekeeper.remote.test;

import java.nio.file.Files;
import java.nio.file.Path;
import net.resheim.eclipse.timekeeper.db.DatabaseStartup;
import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;

/** Loaded only after remote preferences are set; this explicit fixture is not the app connection. */
public final class NativeDatabaseFixture {
    public static void prepare(Path folder) throws Exception {
        if(Files.exists(folder.resolve("native-local.mv.db"))) return;
        var manager=DatabaseStartup.open("jdbc:h2:"+folder.resolve("native-local"));
        var factory=manager.getEntityManagerFactory();
        try {new DefaultTimekeeperService(new JpaServicePorts(()->manager).ports(event->{})).createProject(new CreateProject("Preserve this local project"));}
        finally {manager.close();factory.close();}
    }
}
