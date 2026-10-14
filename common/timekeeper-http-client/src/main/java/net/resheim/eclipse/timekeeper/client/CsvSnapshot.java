package net.resheim.eclipse.timekeeper.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Owner-neutral CSV representation of the domain transfer API; no database is opened. */
public final class CsvSnapshot {
    private static final int MAX_BYTES = 48 * 1024 * 1024;
    private static final Map<String,List<String>> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("projects", List.of("id", "name"));
        COLUMNS.put("tasks", List.of("id", "projectId", "parentTaskId", "summary", "url", "externalReferences"));
        COLUMNS.put("labels", List.of("id", "name", "color"));
        COLUMNS.put("activities", List.of("id", "taskId", "start", "end", "summary", "labelIds", "manual"));
    }
    private static Map<String,List<String>> columns(boolean sessions,boolean clients) {
        var columns=new LinkedHashMap<>(COLUMNS);
        if(sessions) {
            columns.put("activities",List.of("id","taskId","start","end","summary","labelIds","manual","workSessionId","projectId"));
            columns.put("workSessions",List.of("id","start","end","summary"));
        }
        if(clients) {
            columns.put("clients",List.of("id","name","color"));
            columns.put("projects",List.of("id","name","clientId"));
            columns.put("workSessions",List.of("id","start","end","summary","clientId","title"));
        }
        return columns;
    }
    private CsvSnapshot() { }

    /** Writes into a new subfolder chosen by the UI; never replaces an earlier export. */
    public static int write(JsonObject snapshot, Path folder) throws IOException {
        boolean clients="timekeeper-domain-v3".equals(snapshot.get("format").getAsString());
        boolean sessions=clients || "timekeeper-domain-v2".equals(snapshot.get("format").getAsString());
        if (!sessions && !"timekeeper-domain-v1".equals(snapshot.get("format").getAsString())) throw invalid();
        if(!clients) {
            if(snapshot.has("clients") && !snapshot.getAsJsonArray("clients").isEmpty()) throw invalid();
            for(var item:snapshot.getAsJsonArray("projects")) if(item.getAsJsonObject().has("clientId")) throw invalid();
            if(snapshot.has("workSessions")) for(var item:snapshot.getAsJsonArray("workSessions"))
                if(item.getAsJsonObject().has("clientId") || item.getAsJsonObject().has("title")) throw invalid();
        }
        if(!sessions) {
            if(snapshot.has("workSessions") && !snapshot.getAsJsonArray("workSessions").isEmpty()) throw invalid();
            for(var item:snapshot.getAsJsonArray("activities")) {
                var activity=item.getAsJsonObject();if(activity.get("taskId")==null || activity.get("taskId").isJsonNull()) throw invalid();
                for(var column:List.of("projectId","workSessionId")) if(activity.has(column) && !activity.get(column).isJsonNull()) throw invalid();
            }
        }
        ZoneId.of(snapshot.get("zoneId").getAsString());
        Files.createDirectory(folder);
        int count = 0;
        for (var table : columns(sessions,clients).entrySet()) {
            checkCancelled();
            StringBuilder csv = new StringBuilder();row(csv, table.getValue());
            JsonArray items = snapshot.getAsJsonArray(table.getKey());
            for (JsonElement item : items) {
                List<String> fields = new ArrayList<>();
                for (String column : table.getValue()) {
                    JsonElement value = item.getAsJsonObject().get(column);
                    if (value == null && !List.of("workSessionId","projectId").contains(column)) throw invalid();
                    if(value==null) value=JsonNull.INSTANCE;
                    fields.add(value.isJsonNull() ? null : value.isJsonPrimitive() ? value.getAsString() : value.toString());
                }
                row(csv, fields);count++;
            }
            Files.writeString(folder.resolve(table.getKey() + ".csv"), csv, StandardCharsets.UTF_8);
        }
        JsonObject metadata = new JsonObject();metadata.addProperty("format", clients ? "timekeeper-domain-csv-v3" : sessions ? "timekeeper-domain-csv-v2" : "timekeeper-domain-csv-v1");
        metadata.add("zoneId", snapshot.get("zoneId"));
        // The descriptor is last: incomplete/cancelled exports cannot be imported.
        Files.writeString(folder.resolve("timekeeper.csv.json"), metadata.toString(), StandardCharsets.UTF_8);
        return count;
    }

    public static JsonObject read(Path folder) throws IOException {
        long[] remaining = { MAX_BYTES };
        try {
            JsonObject metadata = JsonParser.parseString(readFile(folder.resolve("timekeeper.csv.json"), remaining)).getAsJsonObject();
            boolean clients="timekeeper-domain-csv-v3".equals(metadata.get("format").getAsString());
            boolean sessions=clients || "timekeeper-domain-csv-v2".equals(metadata.get("format").getAsString());
            if (!sessions && !"timekeeper-domain-csv-v1".equals(metadata.get("format").getAsString())) throw invalid();
            ZoneId.of(metadata.get("zoneId").getAsString());
            JsonObject snapshot = new JsonObject();snapshot.addProperty("format", clients ? "timekeeper-domain-v3" : sessions ? "timekeeper-domain-v2" : "timekeeper-domain-v1");
            snapshot.add("zoneId", metadata.get("zoneId"));
            for (var table : columns(sessions,clients).entrySet()) {
                checkCancelled();
                List<List<String>> rows = parse(readFile(folder.resolve(table.getKey() + ".csv"), remaining));
                if (rows.isEmpty() || !rows.removeFirst().equals(table.getValue()) || rows.size() > 10000) throw invalid();
                JsonArray items = new JsonArray();
                for (List<String> fields : rows) {
                    checkCancelled();
                    if (fields.size() != table.getValue().size()) throw invalid();
                    JsonObject item = new JsonObject();
                    for (int i = 0; i < fields.size(); i++) {
                        String column = table.getValue().get(i), value = fields.get(i);
                        if (value == null) item.add(column, JsonNull.INSTANCE);
                        else if (column.equals("externalReferences") || column.equals("labelIds")) {
                            JsonElement array = JsonParser.parseString(value);if (!array.isJsonArray()) throw invalid();item.add(column, array);
                        } else if (column.equals("manual")) {
                            if (!value.equals("true") && !value.equals("false")) throw invalid();item.addProperty(column, Boolean.parseBoolean(value));
                        } else item.addProperty(column, value);
                    }
                    items.add(item);
                }
                snapshot.add(table.getKey(), items);
            }
            if (snapshot.toString().getBytes(StandardCharsets.UTF_8).length > 20 * 1024 * 1024)
                throw new IOException("The CSV data exceeds the server's 20 MiB import limit.");
            return snapshot;
        } catch (RemoteFailure cancelled) {
            throw cancelled;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static String readFile(Path file, long[] remaining) throws IOException {
        checkCancelled();
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes((int) remaining[0] + 1);
            if (bytes.length > remaining[0]) throw new IOException("The CSV export exceeds the 48 MiB file limit.");
            remaining[0] -= bytes.length;
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
    private static void row(StringBuilder target, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) target.append(',');
            String value = values.get(i);
            if (value != null) target.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        target.append("\r\n");
    }
    /** Quoted empty text differs from unquoted empty (null); newlines are allowed inside quotes. */
    private static List<List<String>> parse(String source) throws IOException {
        List<List<String>> rows = new ArrayList<>();List<String> fields = new ArrayList<>();
        int offset = 0;
        while (offset < source.length()) {
            checkCancelled();
            String value = null;
            if (source.charAt(offset) == '"') {
                offset++;StringBuilder text = new StringBuilder();boolean closed = false;
                while (offset < source.length()) {
                    char c = source.charAt(offset++);
                    if (c != '"') text.append(c);
                    else if (offset < source.length() && source.charAt(offset) == '"') {text.append('"');offset++;}
                    else {closed = true;break;}
                }
                if (!closed) throw invalid();value = text.toString();
            } else {
                int start = offset;
                while (offset < source.length() && ",\r\n".indexOf(source.charAt(offset)) < 0) {
                    if (source.charAt(offset) == '"') throw invalid();offset++;
                }
                if (offset != start) value = source.substring(start, offset);
            }
            fields.add(value);
            if (offset == source.length()) {rows.add(fields);break;}
            char separator = source.charAt(offset++);
            if (separator == ',') {if (offset == source.length()) {fields.add(null);rows.add(fields);}continue;}
            if (separator != '\r' && separator != '\n') throw invalid();
            if (separator == '\r' && offset < source.length() && source.charAt(offset) == '\n') offset++;
            rows.add(fields);fields = new ArrayList<>();
            if (rows.size() > 10001) throw invalid();
        }
        return rows;
    }
    private static IOException invalid() {return new IOException("Invalid or incomplete Timekeeper domain CSV export.");}
    private static void checkCancelled() {if (Thread.currentThread().isInterrupted()) throw new RemoteFailure(RemoteFailure.Kind.CANCELLED, "CANCELLED");}
}
