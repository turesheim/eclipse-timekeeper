// Frontmatter is opaque metadata: never parse it into the rich-text document.
export function splitMarkdown(value) {
  const lines=value.match(/[^\n]*\n|[^\n]+$/g)||[];
  if(!/^\uFEFF?---\r?\n$/.test(lines[0]||''))return {frontmatter:'',body:value,unterminated:false};
  let length=lines[0].length;
  for(const line of lines.slice(1)){
    length+=line.length;
    if(/^(?:---|\.\.\.)(?:\r?\n)?$/.test(line))return {frontmatter:value.slice(0,length),body:value.slice(length),unterminated:false};
  }
  // A lone rule, or a rule separated from following Markdown by a blank line,
  // is not an unfinished YAML header. Closed frontmatter above stays opaque.
  if(lines.length===1||lines.slice(1).every(line=>!line.trim())||!lines[1].trim())return {frontmatter:'',body:value,unterminated:false};
  return {frontmatter:'',body:value,unterminated:true};
}

// Conservative admission to rich mode. Unknown dialects remain editable as exact
// source; code examples containing dialect syntax are ordinary supported Markdown.
const withoutAutolinks=value=>value.replace(/<([^<>\s]+)>/g,(match,target)=>{
  const email=/^[^:@<>\s]+@[^<>\s]+\.[^<>\s]+$/.test(target);
  if(!email&&!/^(?:https?:\/\/|mailto:)/i.test(target))return match;
  try{return ['https:','http:','mailto:'].includes(new URL(email?'mailto:'+target:target).protocol)?'':match;}catch{return match;}
}).replace(/\b(?:https?:\/\/|mailto:)[^\s<>()\[\]]+/gi,'');
export const hasUnsupportedSyntax=value=>/\[\[|\]\]|\[![^\]]+\]|==|%%|\$|<|!\[|\[\^|^ {0,3}\[[^\]]+\]:|(?:^|\s)\^[\w-]+|^ {0,3}:::/m.test(
  withoutAutolinks(value).replace(/!\[[^\]\n]*\]\(assets\/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\)/g,''));
export function needsSource(body) {
  let fence=null,text='';
  for(const line of body.split(/\r?\n/)){
    const match=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if(fence){if(match&&match[1][0]===fence[0]&&match[1].length>=fence.length&&!match[2].trim())fence=null;continue;}
    if(match){fence=match[1];continue;}
    text+=line.replace(/(`+)([\s\S]*?)\1/g,'')+'\n';
  }
  return hasUnsupportedSyntax(text);
}

// Milkdown emits this HTML placeholder for an empty paragraph created with
// Enter. It is not part of the note: keeping it would force source-only mode
// after the next save. Markdown blank lines are the closest representable form.
export function normalizeEditorMarkdown(value) {
  let fence=null;
  return (value.match(/[^\n]*\n|[^\n]+$/g)||[]).map(line=>{
    const content=line.replace(/\r?\n$/,''),marker=content.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if(marker){
      if(!fence)fence=marker[1];
      else if(marker[1][0]===fence[0]&&marker[1].length>=fence.length&&!marker[2].trim())fence=null;
    }
    return !fence&&/^\s*<br\s*\/?>\s*$/i.test(content)?line.slice(content.length):line;
  }).join('');
}
