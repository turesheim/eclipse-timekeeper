package net.resheim.eclipse.timekeeper.ui.client;

import org.eclipse.equinox.security.storage.*;

/** Tokens use Equinox encrypted storage and never enter ordinary workspace preferences. */
public final class BackendCredentials {
    private BackendCredentials() { }
    private static ISecurePreferences node() {
        var root=SecurePreferencesFactory.getDefault();if(root==null) throw new IllegalStateException("Eclipse secure storage is unavailable");
        return root.node("/net.resheim.eclipse.timekeeper/backend");
    }
    public static String read() {
        try {return node().get("token","");}catch(StorageException error) {throw new IllegalStateException("Could not read the Timekeeper token from secure storage");}
    }
    public static void save(String token) {
        try {var node=node();if(token.isBlank()) node.remove("token");else node.put("token",token,true);node.flush();}
        catch(Exception error) {throw new IllegalStateException("Could not save the Timekeeper token in secure storage");}
    }
}
