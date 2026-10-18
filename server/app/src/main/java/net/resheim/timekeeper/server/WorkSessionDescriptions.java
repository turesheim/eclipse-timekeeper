package net.resheim.timekeeper.server;

import com.fasterxml.jackson.databind.JsonNode;

/** Fold the retired web-only title into the single work-session description. */
final class WorkSessionDescriptions {
    private WorkSessionDescriptions() { }
    static String summary(JsonNode value) {
        String summary=value.path("summary").asText(""),title=value.path("title").asText("").strip();
        String result=title.isEmpty() || summary.strip().startsWith(title) ? summary
                : summary.isBlank() ? title : title+"\n\n"+summary;
        if(result.codePointCount(0,result.length())>4000) throw ApiFailure.validation();
        return result;
    }
}
