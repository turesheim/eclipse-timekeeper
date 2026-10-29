package net.resheim.timekeeper.server;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Path;

/** Legacy WebDAV URL used by existing clients; no additional storage or protocol. */
@Path("/remote.php/webdav") @Blocking
public class JournalDavAliasResource extends JournalDavResource { }
