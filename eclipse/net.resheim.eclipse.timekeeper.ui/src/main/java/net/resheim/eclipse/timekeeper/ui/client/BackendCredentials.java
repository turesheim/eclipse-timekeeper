package net.resheim.eclipse.timekeeper.ui.client;

import java.io.IOException;
import org.eclipse.equinox.security.storage.*;

/** Tokens use Equinox encrypted storage and never enter ordinary workspace preferences. */
public final class BackendCredentials {
    private BackendCredentials() { }
    private static ISecurePreferences node() {
        var root=SecurePreferencesFactory.getDefault();if(root==null) throw new IllegalStateException("Eclipse secure storage is unavailable. Open General > Security > Secure Storage in Preferences.");
        return root.node("/net.resheim.eclipse.timekeeper/backend");
    }
    public static String read() {
        try {return node().get("token","");}catch(StorageException error) {throw failure("read",error);}
    }
    public static void save(String token) {
        try {var node=node();if(token.isBlank()) node.remove("token");else node.put("token",token,true);node.flush();}
        catch(StorageException error) {throw failure("save",error);}
        catch(IOException error) {throw new IllegalStateException("Could not write Eclipse secure storage. Check its location and file permissions in General > Security > Secure Storage preferences.",error);}
    }
    private static IllegalStateException failure(String operation,StorageException error) {
        String reason=switch(error.getErrorCode()) {
            case StorageException.NO_SECURE_MODULE -> "No secure password provider is available.";
            case StorageException.NO_PASSWORD -> "The secure-storage master password is unavailable.";
            case StorageException.ENCRYPTION_ERROR -> "Eclipse could not encrypt the token.";
            case StorageException.DECRYPTION_ERROR -> "Eclipse could not decrypt the saved token.";
            default -> "Secure-storage error " + error.getErrorCode() + ".";
        };
        return new IllegalStateException("Could not " + operation + " the Timekeeper token. " + reason
                + " Open General > Security > Secure Storage in Preferences.",error);
    }
}
