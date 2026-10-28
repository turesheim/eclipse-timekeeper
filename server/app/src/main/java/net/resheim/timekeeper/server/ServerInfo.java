package net.resheim.timekeeper.server;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.Startup;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Immutable build identity and separately supplied deployment time. */
@Startup @ApplicationScoped @Path("/api/v1/server") @Blocking @Produces("application/json")
public class ServerInfo {
    @ConfigProperty(name="timekeeper.deployed-at") Optional<String> deployment;
    @ConfigProperty(name="timekeeper.spk-version") Optional<String> spkVersion;
    private ObjectNode information;

    @PostConstruct void initialize() {
        var build=new Properties();
        try(var input=ServerInfo.class.getResourceAsStream("/timekeeper-build.properties")) {
            if(input==null) throw new IllegalStateException("Missing server build information");
            build.load(input);
        } catch(IOException failure) {throw new IllegalStateException("Could not read server build information",failure);}
        information=JsonValues.object();
        information.put("version",build.getProperty("version"));
        information.put("spkVersion",spkVersion.orElse(null));
        String commit=build.getProperty("commit","");
        if(commit.matches("[0-9a-f]{40}")) information.put("commit",commit);else information.putNull("commit");
        String dirty=build.getProperty("dirty","");
        if(dirty.equals("true") || dirty.equals("false")) information.put("dirty",Boolean.parseBoolean(dirty));else information.putNull("dirty");
        information.put("builtAt",Instant.parse(build.getProperty("builtAt")).toString());
        if(deployment.filter(value->!value.isBlank()).isPresent())
            information.put("deployedAt",Instant.parse(deployment.orElseThrow()).toString());
        else information.putNull("deployedAt");
        information.put("startedAt",Instant.now().toString());
    }

    @GET public Response get() {return new ApiReplies(200,information.deepCopy(),null,null).response();}
    ObjectNode journalIdentity() {
        var result=JsonValues.object();
        for(String name:java.util.List.of("version","commit","dirty"))result.set(name,information.get(name));
        return result;
    }
    String spkVersion() {return spkVersion.orElse(null);}
}
