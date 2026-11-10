package net.resheim.timekeeper.connect;

import java.net.URI;
import java.util.Optional;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** App registration exists only at the shared service, never on user installations. */
@ApplicationScoped
class ConnectSettings {
    @ConfigProperty(name="timekeeper.connect.origin") String publicOrigin;
    @ConfigProperty(name="timekeeper.connect.encryption-key") String encryptionKey;
    @ConfigProperty(name="timekeeper.connect.google.client-id") Optional<String> googleId;
    @ConfigProperty(name="timekeeper.connect.google.client-secret") Optional<String> googleSecret;
    @ConfigProperty(name="timekeeper.connect.microsoft.client-id") Optional<String> microsoftId;
    @ConfigProperty(name="timekeeper.connect.microsoft.client-secret") Optional<String> microsoftSecret;
    URI origin(){return ConnectProtocol.origin(publicOrigin);}
    String encryptionKey(){return encryptionKey;}
    record App(String id,String secret,String redirect){@Override public String toString(){return "OAuth application registration";}}
    App app(String provider){
        ConnectProtocol.provider(provider);var id=provider.equals("google")?googleId:microsoftId;var secret=provider.equals("google")?googleSecret:microsoftSecret;
        if(id.isEmpty()||secret.isEmpty())throw new ConnectFailure(503,"SIGN_IN_UNAVAILABLE");
        return new App(id.get(),secret.get(),origin()+"/oauth/"+provider+"/callback");
    }
    boolean available(String provider){try{app(provider);return true;}catch(ConnectFailure unavailable){return false;}}
}
