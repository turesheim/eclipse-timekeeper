package net.resheim.timekeeper.server;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Path;

/** Attachments through the same legacy collection alias as journal documents. */
@Path("/remote.php/webdav/assets") @Blocking
public class JournalAssetDavAliasResource extends JournalAssetDavResource { }
