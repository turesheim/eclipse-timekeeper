package net.resheim.timekeeper.server;

import java.util.List;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.parser.delimiter.*;

/** The inline note dialect shared by derived titles and full-text projection. */
final class NotesMarkdown {
    private NotesMarkdown(){}
    static Parser parser(){return Parser.builder().extensions(List.of(YamlFrontMatterExtension.create()))
            .customDelimiterProcessor(new Style('=',2)).customDelimiterProcessor(new Style('~',2)).customDelimiterProcessor(new Style('~',1)).customDelimiterProcessor(new Style('^',1)).build();}
    private static final class InlineStyle extends CustomNode{}
    private record Style(char marker,int size) implements DelimiterProcessor {
        public char getOpeningCharacter(){return marker;}
        public char getClosingCharacter(){return marker;}
        public int getMinLength(){return size;}
        public int process(DelimiterRun opening,DelimiterRun closing){
            if(opening.originalLength()!=size||closing.originalLength()!=size)return 0;
            Node first=opening.getOpener().getNext(),end=closing.getCloser();if(first==end)return 0;
            for(Node node=first;node!=end;node=node.getNext())if(!allowed(node))return 0;
            var style=new InlineStyle();opening.getOpener().insertAfter(style);
            for(Node node=first;node!=end;){Node next=node.getNext();style.appendChild(node);node=next;}
            return size;
        }
        private boolean allowed(Node node){
            if(node instanceof SoftLineBreak||node instanceof HardLineBreak)return false;
            if(size==1&&node instanceof Text text&&text.getLiteral().codePoints().anyMatch(Character::isWhitespace))return false;
            for(Node child=node.getFirstChild();child!=null;child=child.getNext())if(!allowed(child))return false;
            return true;
        }
    }
}
