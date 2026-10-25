// Frontmatter is opaque metadata: never parse it into the rich-text document.
export function splitMarkdown(value) {
  const lines=value.match(/[^\n]*\n|[^\n]+$/g)||[];
  if(!/^\uFEFF?---\r?\n$/.test(lines[0]||''))return {frontmatter:'',body:value,unterminated:false};
  let length=lines[0].length;
  for(const line of lines.slice(1)){
    length+=line.length;
    if(/^(?:---|\.\.\.)(?:\r?\n)?$/.test(line))return {frontmatter:value.slice(0,length),body:value.slice(length),unterminated:false};
  }
  return {frontmatter:'',body:value,unterminated:true};
}

// Conservative admission to rich mode. Unknown dialects remain editable as exact
// source; code examples containing dialect syntax are ordinary supported Markdown.
export function needsSource(body) {
  let fence=null,text='';
  for(const line of body.split(/\r?\n/)){
    const match=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if(fence){if(match&&match[1][0]===fence[0]&&match[1].length>=fence.length&&!match[2].trim())fence=null;continue;}
    if(match){fence=match[1];continue;}
    text+=line.replace(/(`+)([\s\S]*?)\1/g,'')+'\n';
  }
  return /\[\[|\]\]|\[![^\]]+\]|==|%%|\$|<|!\[|\[\^|^ {0,3}\[[^\]]+\]:|(?:^|\s)\^[\w-]+|^ {0,3}:::/m.test(text);
}
