package net.resheim.timekeeper.server;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.text.Normalizer;
import java.util.*;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** A derived text projection. Source bytes, dates, versions and ETags stay owned by SQL. */
final class NotesSearchText {
    private static final Parser MARKDOWN=NotesMarkdown.parser();
    record Projection(String title,List<String> blocks,List<String> tags){
        Projection{blocks=List.copyOf(blocks);tags=List.copyOf(tags);}
        String body(){return String.join("\n\n",blocks);}
        @Override public String toString(){return "NotesSearchText.Projection[blocks="+blocks.size()+"]";}
    }
    static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFC);}
    static Projection extract(byte[] bytes){
        final String source;
        try{source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("Invalid search source encoding");}
        var document=MARKDOWN.parse(source.startsWith("\ufeff")?source.substring(1):source);
        var blocks=new ArrayList<String>();collect(document,blocks);
        var tags=new ArrayList<String>();collectTags(document,tags);
        return new Projection(normalized(NoteTitles.heading(document)),blocks,tags);
    }
    private static void collectTags(Node node,List<String> tags){
        if(node instanceof YamlFrontMatterBlock||node instanceof FencedCodeBlock||node instanceof IndentedCodeBlock||node instanceof HtmlBlock)return;
        if(node instanceof Paragraph||node instanceof Heading){
            var text=new StringBuilder();node.accept(new AbstractVisitor(){
                @Override public void visit(Text value){text.append(value.getLiteral());}
                @Override public void visit(Code value){text.append(' ');}
                @Override public void visit(Link value){text.append(' ');}
                @Override public void visit(Image value){text.append(' ');}
                @Override public void visit(SoftLineBreak value){text.append('\n');}
                @Override public void visit(HardLineBreak value){text.append('\n');}
            });String value=NotesHashtags.prose(text.toString());if(value.indexOf('#')>=0)tags.add(value);return;
        }
        for(var child=node.getFirstChild();child!=null;child=child.getNext())collectTags(child,tags);
    }
    private static void collect(Node node,List<String> blocks){
        if(node instanceof YamlFrontMatterBlock)return;
        if(node instanceof FencedCodeBlock code){add(blocks,code.getLiteral());return;}
        if(node instanceof IndentedCodeBlock code){add(blocks,code.getLiteral());return;}
        if(node instanceof HtmlBlock html){add(blocks,htmlText(html.getLiteral()));return;}
        if(node instanceof Paragraph||node instanceof Heading){
            var text=new StringBuilder();node.accept(new AbstractVisitor(){
                @Override public void visit(Text value){text.append(value.getLiteral());}
                @Override public void visit(Code value){text.append(value.getLiteral());}
                @Override public void visit(SoftLineBreak value){text.append('\n');}
                @Override public void visit(HardLineBreak value){text.append('\n');}
            });add(blocks,text.toString());return;
        }
        for(var child=node.getFirstChild();child!=null;child=child.getNext())collect(child,blocks);
    }
    private static void add(List<String> blocks,String text){String value=normalized(text.strip());if(!value.isEmpty())blocks.add(value);}
    /** HTML is data here: discard tag syntax without parsing/loading resources. */
    private static String htmlText(String source){
        var text=new StringBuilder();
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(source.startsWith("<!--",i)){int end=source.indexOf("-->",i+4);if(end<0)break;i=end+2;continue;}
            if(c=='<'&&i+1<source.length()&&(Character.isLetter(source.charAt(i+1))||"/!?".indexOf(source.charAt(i+1))>=0)){
                int end=i+1;char quote=0;
                for(;end<source.length();end++){char next=source.charAt(end);if(quote!=0){if(next==quote)quote=0;}else if(next=='\''||next=='"')quote=next;else if(next=='>')break;}
                if(end<source.length()){text.append(' ');i=end;continue;}
            }
            if(c=='&'){
                int end=source.indexOf(';',i+1);
                if(end>i&&end-i<=12){String entity=source.substring(i+1,end),value=entity(entity);if(value!=null){text.append(value);i=end;continue;}}
            }
            text.append(c);
        }
        return text.toString();
    }
    private static String entity(String value){
        switch(value){case "amp":return "&";case "lt":return "<";case "gt":return ">";case "quot":return "\"";case "apos":return "'";case "nbsp":return " ";default:break;}
        if(value.startsWith("#"))try{int number=value.startsWith("#x")||value.startsWith("#X")?Integer.parseInt(value.substring(2),16):Integer.parseInt(value.substring(1));if(Character.isValidCodePoint(number)&&(number<0xd800||number>0xdfff))return new String(Character.toChars(number));}
        catch(IllegalArgumentException invalid){return null;}
        return null;
    }
}
