import {MarkdownField} from '/web/markdown-field.js';

// File loading and capture are fixture adapters, not application controls.
const field=new MarkdownField(document.getElementById('markdown-field'),()=>{},()=>{},'markdown');
field.formatted.id='markdown-rich';field.source.id='markdown-source';
field.status.id='markdown-status';field.tools.id='markdown-toolbar';
field.metadata.id='markdown-metadata';field.frontmatterView.id='markdown-frontmatter';
field.setReadOnly(false);field.setValue('# Daily notes\n\n- [ ] Follow up\n');
document.getElementById('markdown-file').addEventListener('change',async event=>{
  const file=event.target.files[0];if(!file)return;
  field.setValue(new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(await file.arrayBuffer()));
  field.status.textContent='Opened locally';event.target.value='';
});
document.getElementById('markdown-download').addEventListener('click',()=>{
  const url=URL.createObjectURL(new Blob([field.value()],{type:'text/markdown;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='fixture.md';link.click();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
});
globalThis.fixtureField=field;
