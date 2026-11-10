package net.resheim.timekeeper.connect;

import java.io.IOException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import net.resheim.timekeeper.server.TaskProviderHttp;

final class ConnectResponses {
    @Provider public static class Failures implements ExceptionMapper<ConnectFailure>{
        @Override public Response toResponse(ConnectFailure failure){return error(failure.status,failure.code);}
    }
    @Provider public static class ProviderFailures implements ExceptionMapper<TaskProviderHttp.Failure>{
        @Override public Response toResponse(TaskProviderHttp.Failure failure){return error(failure.status==429?429:failure.status==401||failure.status==403?409:503,failure.status==401||failure.status==403?"RECONNECT_REQUIRED":"SIGN_IN_UNAVAILABLE");}
    }
    @Provider public static class Headers implements ContainerResponseFilter{
        @Override public void filter(ContainerRequestContext request,ContainerResponseContext response)throws IOException{
            var headers=response.getHeaders();headers.putSingle("Cache-Control","no-store");headers.putSingle("Referrer-Policy","no-referrer");headers.putSingle("X-Content-Type-Options","nosniff");
            headers.putSingle("Content-Security-Policy","default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
            if(response.getStatus()==429)headers.putSingle("Retry-After","60");
        }
    }
    private static Response error(int status,String code){return Response.status(status).type("application/json").entity(ConnectProtocol.object().put("status",status).put("code",code)).build();}
}
