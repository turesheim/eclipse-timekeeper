package net.resheim.eclipse.timekeeper.client;

/** Stable transport failures; neither credentials nor raw server responses are exposed. */
public final class RemoteFailure extends RuntimeException {
    public enum Kind { AUTHENTICATION, FORBIDDEN, CONFLICT, VALIDATION, NOT_FOUND, OFFLINE, TIMEOUT, UNAVAILABLE, CANCELLED }
    private final Kind kind;
    private final String code;
    private final int status;
    public RemoteFailure(Kind kind,String code) {
        this(kind,code,0);
    }
    RemoteFailure(Kind kind,String code,int status) {
        super(switch(kind) {
            case AUTHENTICATION -> switch(code) {
                case "OWNER_CHANGED" -> "The account for this session has changed. Start a new session with the intended account.";
                case "INVALID_TOKEN" -> "The token contains invalid characters or is too long. Copy the complete API token and try again.";
                default -> "Sign in to the Timekeeper server with a valid token.";
            };
            case FORBIDDEN -> "This token does not permit the requested change.";
            case CONFLICT -> "The record or active task changed on the server. Refresh and review your changes.";
            case VALIDATION -> "The server rejected these values. Review the fields and try again.";
            case NOT_FOUND -> "This record is no longer available on the server.";
            case OFFLINE -> "The Timekeeper server could not be reached. Your edits have not been copied to local storage.";
            case TIMEOUT -> "The server request timed out. Refresh before repeating a change.";
            case UNAVAILABLE -> "The Timekeeper server is unavailable. Refresh before repeating a change.";
            case CANCELLED -> "The request was cancelled. Refresh to confirm whether the server completed it.";
        });this.kind=kind;this.code=code;this.status=status;
    }
    public Kind kind() {return kind;}
    public String code() {return code;}
    /** Guidance for the read-only connection test, without raw response or exception details. */
    public String connectionMessage(java.net.URI server) {
        String endpoint=HttpTimekeeperService.baseUri(server).toString();
        String detail=switch(kind) {
            case AUTHENTICATION -> switch(code) {
                case "INVALID_TOKEN", "OWNER_CHANGED" -> getMessage();
                default -> "The server rejected this token. Check that the Server URL matches the browser where you created it. Copy a complete, valid token from that server's My Account > API tokens and check that it has not expired or been revoked.";
            };
            case FORBIDDEN -> "The server refused access. Check the token's read permission and any reverse proxy access rules.";
            case NOT_FOUND -> "No Timekeeper API was found at this address. Check the Server URL, including its port and path.";
            case OFFLINE -> "TLS_ERROR".equals(code)
                    ? "The secure connection failed. Check the server's HTTPS certificate and whether Java trusts its certificate authority."
                    : "The server could not be reached. Check the Server URL, network or VPN connection, and whether the server is running.";
            case TIMEOUT -> "The server did not respond in time. Check the network or VPN connection and whether the server is responding.";
            case UNAVAILABLE -> status>=300 && status<400
                    ? "The server redirected the request. Enter the final Timekeeper server URL; tokens are not forwarded to another address."
                    : "INVALID_RESPONSE".equals(code)
                            ? "The server did not return a valid Timekeeper response. Check the Server URL and reverse proxy configuration."
                            : "The server is unavailable. Check its health and reverse proxy configuration, then try again.";
            default -> getMessage();
        };
        return endpoint+(status>0 ? " (HTTP "+status+")" : "")+": "+detail;
    }
}
