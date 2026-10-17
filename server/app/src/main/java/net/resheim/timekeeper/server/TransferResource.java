package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@Path("/api/v1/data") @Blocking @Consumes("application/json") @Produces("application/json")
public class TransferResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    private static final Map<String,String> TYPES=DomainTransfer.TYPES;
    @GET @Path("/export") public Response exportData() {
        ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> {
            var value=object();value.put("format","timekeeper-domain-v3");value.put("zoneId",store.profile().get("zoneId").asText());
            TYPES.forEach((name,type) -> {
                var records=store.list(type);if(records.size()>10000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                var array=value.putArray(name);for(JsonNode record:records) {var copy=((ObjectNode)record).deepCopy();copy.remove(List.of("ownerId","version","state"));array.add(copy);}
            });
            if(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>20*1024*1024) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            return new ApiReplies(200,value,null,null).response();
        });
    }
    @POST @Path("/import") public Response importData(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        if(body!=null && body.isObject()) for(String name:TYPES.keySet()) {
            if(body.has(name) && body.get(name).isArray() && body.get(name).size()>10000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        }
        JsonNode input=inputs.validate("TransferSnapshot",body);ContractInputs.zone(input.get("zoneId").asText());ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> receipts.execute(key,"POST","/data/import",null,input,() -> new DomainTransfer(store).importRecords(input)).response());
    }
}
