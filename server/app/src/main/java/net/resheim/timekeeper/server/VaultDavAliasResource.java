package net.resheim.timekeeper.server;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Path;

/** MySync's configurable remote folder maps to the same canonical collection. */
@Path("/remote.php/webdav/vault") @Blocking
public class VaultDavAliasResource extends VaultDavResource { }
