package net.resheim.timekeeper.server;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;

public record ApiReplies(int status,JsonNode body,String etag,String location) {
    public static ApiReplies resource(int status,JsonNode body,String location) { return new ApiReplies(status,body,JsonValues.etag(body),location); }
    public Response response() {
        var builder=Response.status(status).header("Cache-Control","no-store");
        if (body!=null) builder.type("application/json").entity(body);
        if (etag!=null) builder.header("ETag",etag);
        if (location!=null) builder.header("Location",location);
        return builder.build();
    }
    public static long match(JsonNode value,String header) {
        if (header==null) throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        if (!header.matches("\"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\"")) throw ApiFailure.badRequest();
        if (!JsonValues.etag(value).equals(header)) throw new ApiFailure(412,"STALE_VERSION");
        return value.get("version").asLong();
    }
}
