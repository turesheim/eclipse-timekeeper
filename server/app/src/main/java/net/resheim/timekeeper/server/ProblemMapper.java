package net.resheim.timekeeper.server;

import java.util.Locale;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import net.resheim.eclipse.timekeeper.domain.TimekeeperException;

@Provider
public class ProblemMapper implements ExceptionMapper<Exception> {
    @Context UriInfo uri;
    @Override public Response toResponse(Exception error) {
        int status=503; String code="UNAVAILABLE";
        if (error instanceof ApiFailure failure) { status=failure.status; code=failure.code; }
        else if (error instanceof TimekeeperException failure) {
            switch(failure.code()) {
                case VALIDATION -> { status=422; code="VALIDATION"; }
                case NOT_FOUND -> { status=404; code="NOT_FOUND"; }
                case CONFLICT -> { status=409; code="CONFLICT"; }
            }
        } else if (error instanceof WebApplicationException web) {
            status=web.getResponse().getStatus();
            code=switch(status) { case 400->"BAD_REQUEST"; case 404->"NOT_FOUND"; case 413->"PAYLOAD_TOO_LARGE"; case 415->"UNSUPPORTED_MEDIA_TYPE"; default->"UNAVAILABLE"; };
        } else if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) { status=400; code="BAD_REQUEST"; }
        var problem=JsonValues.object(); problem.put("type","urn:timekeeper:problem:"+code.toLowerCase(Locale.ROOT).replace('_','-'));
        problem.put("title",code.replace('_',' ')); problem.put("status",status); problem.put("code",code);
        var response=Response.status(status).type("application/problem+json").entity(problem).header("Cache-Control","no-store");
        if (status==401) response.header("WWW-Authenticate",uri!=null&&uri.getPath().replaceFirst("^/","").startsWith("dav/journal")
                ?"Basic realm=\"Timekeeper journal\"":"Bearer realm=\"timekeeper\""+(error instanceof ApiFailure failure && failure.invalidToken ? ", error=\"invalid_token\"" : ""));
        return response.build();
    }
}
