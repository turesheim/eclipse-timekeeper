package net.resheim.timekeeper.server;

/** Public errors carry stable codes and never expose SQL, tokens or foreign data. */
public final class ApiFailure extends RuntimeException {
    public final int status;
    public final String code;
    public boolean invalidToken;
    public ApiFailure(int status, String code) { this(status,code,null); }
    public ApiFailure(int status, String code, Throwable cause) { super(code,cause); this.status = status; this.code = code; }
    public static ApiFailure unauthorized(boolean invalid) { var failure=new ApiFailure(401,"UNAUTHENTICATED");failure.invalidToken=invalid;return failure; }
    public static ApiFailure badRequest() { return new ApiFailure(400, "BAD_REQUEST"); }
    public static ApiFailure validation() { return new ApiFailure(422, "VALIDATION"); }
    public static ApiFailure notFound() { return new ApiFailure(404, "NOT_FOUND"); }
    public static ApiFailure conflict() { return new ApiFailure(409, "CONFLICT"); }
}
