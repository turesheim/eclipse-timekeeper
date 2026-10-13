package net.resheim.eclipse.timekeeper.jira.test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A strict loopback-only REST fixture. Unknown requests fail the test. */
final class JiraRestFixture implements AutoCloseable {
    enum Auth { BASIC, BEARER }
    static final String USER = "ci-user";
    static final String TOKEN = "synthetic-ci-token";
    private final ServerSocket listener;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "jira-rest-fixture"); t.setDaemon(true); return t;
    });
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final Auth auth;
    private volatile boolean closed;
    volatile boolean offline;
    volatile boolean moved;
    volatile String custom = "option";
    volatile int rejectedCredentials;

    JiraRestFixture(Auth auth, int port) throws IOException {
        this.auth = auth;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        executor.submit(() -> {
            while (!closed) {
                try { var socket = listener.accept(); executor.submit(() -> serve(socket)); }
                catch (IOException e) { if (!closed) unexpected.add(e.toString()); }
            }
        });
    }
    int port() { return listener.getLocalPort(); }
    String url() { return "http://127.0.0.1:" + port() + (auth == Auth.BEARER ? "/jira" : ""); }
    List<String> requests() { return List.copyOf(requests); }
    List<String> unexpected() { return List.copyOf(unexpected); }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10000);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var line = reader.readLine();
            if (line == null) return;
            var parts = line.split(" ", 3);
            var headers = new HashMap<String, String>();
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length > 0) {
                var body = new char[length]; int read = 0;
                while (read < length) { int n = reader.read(body, read, length - read); if (n < 0) break; read += n; }
            }
            String requestPath = URI.create(parts[1]).getPath();
            if (auth == Auth.BEARER && requestPath.startsWith("/jira/")) requestPath = requestPath.substring(5);
            String path = requestPath.replace("/rest/api/latest/", "/rest/api/2/");
            int status = 200;
            String expectedAuth = auth == Auth.BASIC
                    ? "Basic " + Base64.getEncoder().encodeToString((USER + ":" + TOKEN).getBytes(StandardCharsets.UTF_8))
                    : "Bearer " + TOKEN;
            String body;
            if (path.endsWith(".png")) {
                body = "";
            } else if (!expectedAuth.equals(headers.get("authorization"))) {
                status = 401; rejectedCredentials++; body = "{\"errorMessages\":[\"Invalid fixture credentials\"],\"errors\":{}}";
            } else if (offline) {
                status = 503; body = "{\"errorMessages\":[\"Fixture offline\"],\"errors\":{}}";
            } else {
                body = response(path);
                if (body == null) {
                    status = 404; body = "{\"errorMessages\":[\"Issue not found\"],\"errors\":{}}";
                    if (!path.equals("/rest/api/2/issue/9999")) unexpected.add(parts[0] + " " + parts[1]);
                }
            }
            if (!parts[0].equals("GET") && !(parts[0].equals("POST") && path.startsWith("/rest/api/2/search")))
                unexpected.add("Unexpected mutation: " + parts[0] + " " + path);
            requests.add(parts[0] + " " + parts[1] + " -> " + status + " (" + auth + ")");
            byte[] bytes = path.endsWith(".png") ? Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=") : body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Fixture response")
                    + "\r\nContent-Type: " + (path.endsWith(".png") ? "image/png" : "application/json; charset=UTF-8") + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
        } catch (Exception e) { if (!closed) unexpected.add(e.toString()); }
    }

    private String response(String path) {
        if (path.equals("/rest/api/2/myself") || path.equals("/rest/api/2/user")) return user();
        if (path.equals("/rest/api/2/serverInfo")) return """
            {"baseUrl":"%s","version":"9.12.0","versionNumbers":[9,12,0],"buildNumber":912000,
             "buildDate":"2024-01-01T00:00:00.000+0000","serverTime":"2026-10-12T09:00:00.000+0000",
             "scmInfo":"ci-fixture","serverTitle":"Timekeeper CI","deploymentType":"%s"}
            """.formatted(url(), auth == Auth.BASIC ? "Cloud" : "Server");
        if (path.equals("/rest/api/2/user/assignable/search") || path.equals("/rest/api/2/user/search")) return "[" + user() + "]";
        if (path.equals("/rest/api/2/project")) return "[" + project() + "]";
        if (path.equals("/rest/api/2/project/TK")) return project();
        if (path.contains("securitylevel")) return "{\"self\":\"" + url() + path + "\",\"id\":\"10001\",\"name\":\"CI security level\",\"description\":\"Synthetic level\"}";
        if (path.equals("/rest/api/2/priority")) return "[" + priority() + "]";
        if (path.equals("/rest/api/2/status")) return "[" + status() + "]";
        if (path.equals("/rest/api/2/issuetype")) return "[" + issueType() + "]";
        if (path.equals("/rest/api/2/resolution") || path.equals("/rest/api/2/field") || path.endsWith("/remotelink")) return "[]";
        if (path.startsWith("/rest/api/2/search")) return "{\"startAt\":0,\"maxResults\":100,\"total\":2,\"isLast\":true,\"names\":{\"customfield_10001\":\"Team\"},\"schema\":{\"customfield_10001\":{\"type\":\"string\"}},\"issues\":[" + issue("1042") + "," + issue("1043") + "]}";
        if (path.endsWith("/transitions")) return "{\"transitions\":[]}";
        if (path.endsWith("/editmeta")) return "{\"fields\":" + metadata() + "}";
        if (path.equals("/rest/api/2/issue/1042") || path.equals("/rest/api/2/issue/TK-42") || path.equals("/rest/api/2/issue/MOVED-42")) return issue("1042");
        if (path.equals("/rest/api/2/issue/1043") || path.equals("/rest/api/2/issue/TK-43")) return issue("1043");
        return null;
    }
    private String user() { return """
        {"self":"%s/rest/api/2/user?username=ci-user","name":"ci-user","key":"ci-user","accountId":"ci-account",
         "displayName":"Synthetic CI user","emailAddress":"ci@example.test","active":true,"timeZone":"UTC",
         "avatarUrls":{"48x48":"%s/avatar.png","16x16":"%s/avatar.png"},"groups":{"size":0,"items":[]},"applicationRoles":{"size":0,"items":[]}}
        """.formatted(url(), url(), url()); }
    private String issueType() { return "{\"self\":\"" + url() + "/rest/api/2/issuetype/1\",\"id\":\"1\",\"name\":\"Task\",\"description\":\"CI task\",\"iconUrl\":\"" + url() + "/task.png\",\"subtask\":false}"; }
    private String priority() { return "{\"self\":\"" + url() + "/rest/api/2/priority/3\",\"id\":\"3\",\"name\":\"Medium\",\"statusColor\":\"#009900\",\"description\":\"Medium priority\",\"iconUrl\":\"" + url() + "/priority.png\"}"; }
    private String status() { return "{\"self\":\"" + url() + "/rest/api/2/status/1\",\"id\":\"1\",\"name\":\"Open\",\"description\":\"Open\",\"iconUrl\":\"" + url() + "/status.png\"}"; }
    private String project() { return "{\"self\":\"" + url() + "/rest/api/2/project/10000\",\"id\":\"10000\",\"key\":\"TK\",\"name\":\"Timekeeper CI\",\"description\":\"Synthetic CI project\",\"url\":\"" + url() + "\",\"lead\":" + user() + ",\"expand\":\"description,lead,issueTypes\",\"components\":[],\"versions\":[],\"issueTypes\":[" + issueType() + "],\"roles\":{},\"avatarUrls\":{\"48x48\":\"" + url() + "/project.png\"}}"; }
    private String option() { return "{\"self\":\"" + url() + "/rest/api/2/customFieldOption/20000\",\"id\":\"20000\",\"value\":\"Platform team\"}"; }
    private String metadata() { return "{\"customfield_10001\":{\"required\":false,\"name\":\"Team\",\"key\":\"customfield_10001\",\"schema\":{\"type\":\"string\",\"custom\":\"com.atlassian.jira.plugin.system.customfieldtypes:" + (custom.equals("blank") ? "textfield" : "select") + "\",\"customId\":10001},\"operations\":[\"set\"],\"allowedValues\":[" + option() + "]}}"; }
    private String issue(String id) {
        String key = id.equals("1042") ? (moved ? "MOVED-42" : "TK-42") : "TK-43";
        String value = !id.equals("1042") || custom.equals("null") ? "null" : custom.equals("blank") ? "\"\"" : option();
        String field = custom.equals("missing") ? "" : ",\"customfield_10001\":" + value;
        return """
            {"id":"%s","key":"%s","self":"%s/rest/api/2/issue/%s","expand":"names,schema,editmeta",
             "names":{"customfield_10001":"Team"},"schema":{},"editmeta":{"fields":%s},
             "fields":{"summary":"Jira CI issue %s","description":"Synthetic fixture","project":%s,
               "issuetype":%s,"status":%s,"priority":%s,"resolution":null,"reporter":%s,"assignee":null,
               "created":"2026-10-09T09:00:00.000+0000","updated":"2026-10-12T09:00:00.000+0000",
               "labels":[],"components":[],"versions":[],"fixVersions":[],"issuelinks":[],"subtasks":[],
               "attachment":[],"comment":{"startAt":0,"maxResults":0,"total":0,"comments":[]},
               "worklog":{"startAt":0,"maxResults":0,"total":0,"worklogs":[]},
               "votes":{"self":"%s/rest/api/2/issue/%s/votes","votes":0,"hasVoted":false},
               "watches":{"self":"%s/rest/api/2/issue/%s/watchers","watchCount":0,"isWatching":false}%s}}
            """.formatted(id, key, url(), id, metadata(), id, project(), issueType(), status(), priority(), user(), url(), id, url(), id, field);
    }
    @Override public void close() throws IOException {
        closed = true; listener.close(); executor.shutdownNow();
        var report = Path.of(System.getProperty("timekeeper.jira.report")); Files.createDirectories(report);
        Files.write(report.resolve("rest-" + port() + ".txt"), requests);
    }
}
