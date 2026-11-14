package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;

/** Account preferences contain plain text, never executable templates. */
final class TextSnippets {
    private TextSnippets(){}
    static JsonNode checked(JsonNode value){
        if(value==null||value.isNull())return null;
        if(!value.isArray()||value.size()>50)throw ApiFailure.validation();
        var keys=new HashSet<String>();int bytes=0;
        for(var item:value){
            if(!item.isObject()||item.size()!=2||!item.path("keyword").isTextual()||!item.path("content").isTextual())throw ApiFailure.validation();
            String keyword=item.path("keyword").asText(),content=item.path("content").asText();
            if(!keyword.matches(";[A-Za-z][A-Za-z0-9_-]{0,31}")||content.isEmpty()||content.codePointCount(0,content.length())>8192||!keys.add(keyword.toLowerCase(Locale.ROOT)))throw ApiFailure.validation();
            bytes+=keyword.getBytes(StandardCharsets.UTF_8).length+content.getBytes(StandardCharsets.UTF_8).length;if(bytes>65536)throw ApiFailure.validation();
        }
        return value.deepCopy();
    }
}
