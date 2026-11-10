package net.resheim.timekeeper.server;

import java.util.*;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.*;
import org.commonmark.parser.*;

/** Checkbox locations come from the Markdown tree, never a scan through code examples. */
final class NoteTaskMarkup {
    private static final Parser MARKDOWN=Parser.builder().extensions(List.of(YamlFrontMatterExtension.create(),StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    record Item(int marker,String summary,boolean checked,int depth){
        @Override public String toString(){return "NoteTaskMarkup.Item[marker="+marker+", checked="+checked+", depth="+depth+"]";}
    }
    static List<Item> items(String source){
        int prefix=source.startsWith("\ufeff")?1:0;String input=source.substring(prefix);var result=new ArrayList<Item>();
        collect(MARKDOWN.parse(input),input,prefix,result);return List.copyOf(result);
    }
    private static void collect(Node node,String input,int prefix,List<Item> result){
        if(node instanceof ListItem&&node.getFirstChild() instanceof Paragraph paragraph&&!paragraph.getSourceSpans().isEmpty()){
            int marker=paragraph.getSourceSpans().get(0).getInputIndex();
            if(marker>=0&&marker+3<=input.length()&&input.charAt(marker)=='['&&input.charAt(marker+2)==']'
                    &&" xX".indexOf(input.charAt(marker+1))>=0&&(marker+3==input.length()||Character.isWhitespace(input.charAt(marker+3)))){
                String summary=summary(paragraph,input.substring(marker,marker+3),marker);
                if(!summary.isEmpty()){
                    int depth=0;for(Node parent=node.getParent();parent!=null;parent=parent.getParent())if(parent instanceof ListItem)depth++;
                    result.add(new Item(marker+prefix,summary,input.charAt(marker+1)!=' ',depth));
                }
            }
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNext())collect(child,input,prefix,result);
    }
    private static String summary(Paragraph paragraph,String marker,int start){
        var text=new StringBuilder();paragraph.accept(new AbstractVisitor(){
            @Override public void visit(Text node){
                String value=node.getLiteral();
                if(node==paragraph.getFirstChild()&&value.startsWith(marker))value=value.substring(marker.length());
                text.append(value);
            }
            @Override public void visit(Code node){text.append(node.getLiteral());}
            @Override public void visit(SoftLineBreak node){text.append(' ');}
            @Override public void visit(HardLineBreak node){text.append(' ');}
            @Override public void visit(Link node){
                // A matching reference definition can turn [x] into a Link in
                // the base parser; it is still the checkbox's raw marker here.
                if(!node.getSourceSpans().isEmpty()&&node.getSourceSpans().stream().allMatch(span->span.getInputIndex()>=start&&span.getInputIndex()+span.getLength()<=start+3))return;
                visitChildren(node);
            }
        });
        return NotesSearchText.normalized(text.toString().strip().replaceAll("(?U)\\s+"," "));
    }
    static String checked(String source,Item item,boolean checked){
        int marker=item.marker();if(marker<0||marker+3>source.length()||source.charAt(marker)!='['||source.charAt(marker+2)!=']'
                ||" xX".indexOf(source.charAt(marker+1))<0||(source.charAt(marker+1)!=' ')!=item.checked())throw new IllegalArgumentException("Checklist source changed");
        if(item.checked()==checked)return source;
        return source.substring(0,marker+1)+(checked?'x':' ')+source.substring(marker+2);
    }
}
