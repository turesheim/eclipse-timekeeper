package net.resheim.eclipse.timekeeper.client;

/** Stable transport failures; neither credentials nor raw server responses are exposed. */
public final class RemoteFailure extends RuntimeException {
    public enum Kind { AUTHENTICATION, FORBIDDEN, CONFLICT, VALIDATION, NOT_FOUND, OFFLINE, TIMEOUT, UNAVAILABLE, CANCELLED }
    private final Kind kind;
    private final String code;
    public RemoteFailure(Kind kind,String code) {
        super(switch(kind) {
            case AUTHENTICATION -> "Sign in to the Timekeeper server with a valid token.";
            case FORBIDDEN -> "This token does not permit the requested change.";
            case CONFLICT -> "The record or active task changed on the server. Refresh and review your changes.";
            case VALIDATION -> "The server rejected these values. Review the fields and try again.";
            case NOT_FOUND -> "This record is no longer available on the server.";
            case OFFLINE -> "The Timekeeper server could not be reached. Your edits have not been copied to local storage.";
            case TIMEOUT -> "The server request timed out. Refresh before repeating a change.";
            case UNAVAILABLE -> "The Timekeeper server is unavailable. Refresh before repeating a change.";
            case CANCELLED -> "The request was cancelled. Refresh to confirm whether the server completed it.";
        });this.kind=kind;this.code=code;
    }
    public Kind kind() {return kind;}
    public String code() {return code;}
}
