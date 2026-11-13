import {t} from './i18n.js';
import {node,button,field,section} from './account-ui.js';
import {defaultSnippets,configureSnippets} from './snippets.js';

export async function renderSnippets(view){
  let loaded=await view.session.request('/me');if(!view.valid())return;
  const card=section(view,t('snippets.title')),form=node('form'),rows=node('div'),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  card.append(node('p',t('snippets.description'),'hint'));
  const help=node('p',t('snippets.help'),'hint');help.id='snippets-help';card.append(help);
  const entries=[];
  function row(value={keyword:'',content:''}){
    const wrap=node('fieldset',undefined,'border rounded p-3 mb-3');wrap.append(node('legend',t('snippets.snippet'),'fs-6'));
    const keyword=field(wrap,t('snippets.keyword'),value.keyword,{maxLength:33});keyword.pattern=';[A-Za-z][A-Za-z0-9_-]{0,31}';keyword.spellcheck=false;
    const label=node('label',t('snippets.content'),'form-label'),content=node('textarea',undefined,'form-control');content.id=keyword.id+'-content';label.htmlFor=content.id;content.value=value.content;content.required=true;content.rows=3;content.maxLength=16384;content.setAttribute('aria-describedby',help.id);
    const entry={keyword,content,wrap};entries.push(entry);keyword.disabled=content.disabled=!view.canWrite;
    const remove=button(t('snippets.remove'),()=>{entries.splice(entries.indexOf(entry),1);wrap.remove();view.dirty.add(form);add.disabled=entries.length>=50||!view.canWrite;});remove.disabled=!view.canWrite;
    wrap.append(label,content,remove);rows.append(wrap);
  }
  function populate(values){entries.length=0;rows.replaceChildren();for(const value of values)row(value);add.disabled=values.length>=50||!view.canWrite;}
  const add=button(t('snippets.add'),()=>{if(entries.length<50){row();entries.at(-1).keyword.focus();view.dirty.add(form);add.disabled=entries.length>=50;}});
  const save=node('button',t('snippets.save'),'btn btn-primary');save.type='submit';
  const reset=button(t('snippets.reset'),()=>void persist(null,reset));
  async function persist(values,submit){
    if(!view.valid()||view.busy)return;
    await view.run(async()=>{
      if(values){
        const keys=new Set();let bytes=0;
        for(const entry of values){const key=entry.keyword.toLowerCase();if(keys.has(key)||[...entry.content].length>8192)throw new Error(t('snippets.invalid'));keys.add(key);bytes+=new TextEncoder().encode(entry.keyword+entry.content).length;}
        if(bytes>65536)throw new Error(t('snippets.invalid'));
      }
      const reply=await view.session.request('/me',{method:'PUT',etag:loaded.etag,body:{zoneId:loaded.data.zoneId,textSnippets:values}});if(!view.valid())return;
      loaded=reply;configureSnippets(reply.data);populate(reply.data.textSnippets??defaultSnippets);view.clean(form);feedback.textContent=t('snippets.saved');void view.refreshProfile(reply).catch(()=>{});
    },{submit,feedback,write:true});
  }
  form.addEventListener('input',()=>view.dirty.add(form));
  form.addEventListener('submit',event=>{event.preventDefault();void persist(entries.map(({keyword,content})=>({keyword:keyword.value,content:content.value})),save);});
  save.disabled=reset.disabled=add.disabled=!view.canWrite;
  const actions=node('div',undefined,'d-flex flex-wrap gap-2');actions.append(add,save,reset);form.append(rows,feedback,actions);card.append(form);populate(loaded.data.textSnippets??defaultSnippets);
}
