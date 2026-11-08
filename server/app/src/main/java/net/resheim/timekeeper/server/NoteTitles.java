package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.uuid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** Display metadata only: titles never rename a document or change its canonical bytes. */
final class NoteTitles {
    static final int MAXIMUM=4096;
    private static final Parser MARKDOWN=Parser.builder()
            .extensions(List.of(YamlFrontMatterExtension.create(),StrikethroughExtension.create())).build();
    private final ServerStore store;
    NoteTitles(ServerStore store){this.store=store;}
    String get(JsonNode entry){
        String source=entry.path("sourceType").asText(),kind=entry.path("kind").asText();
        if(kind.equals("EVENT")&&source.equals(EventNotes.TYPE))
            return store.find(source,uuid(entry.path("sourceId"))).map(value->value.path("event").path("title").asText().strip()).orElse("");
        if(kind.equals("MARKDOWN")&&source.equals(VaultContent.TYPE))
            return store.find(source,uuid(entry.path("sourceId"))).map(value->heading(new String(VaultContent.bytes((ObjectNode)value),StandardCharsets.UTF_8))).orElse("");
        return "";
    }
    static String heading(String markdown){
        if(markdown.startsWith("\ufeff"))markdown=markdown.substring(1);
        // Only document headings supply a title; quoted or code examples do not.
        return heading(MARKDOWN.parse(markdown));
    }
    static String heading(Node document){
        for(Node block=document.getFirstChild();block!=null;block=block.getNext())if(block instanceof Heading){
            var text=new StringBuilder();block.accept(new AbstractVisitor(){
                @Override public void visit(Text node){text.append(node.getLiteral());}
                @Override public void visit(Code node){text.append(node.getLiteral());}
                @Override public void visit(SoftLineBreak node){text.append(' ');}
                @Override public void visit(HardLineBreak node){text.append(' ');}
            });
            String title=text.toString().replaceAll("[\\p{Z}\\s]+"," ").strip();
            if(!title.isEmpty())return title.codePointCount(0,title.length())>MAXIMUM?title.substring(0,title.offsetByCodePoints(0,MAXIMUM)):title;
        }
        return "";
    }
}
