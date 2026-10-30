import {t} from './i18n.js';
import {node,field,form,button} from './account-ui.js';

export async function renderFocusSettings(view,changed){
  const [reply,clients]=await Promise.all([view.session.request('/focus-areas'),view.session.page('/clients')]);
  if(!view.valid())return;
  let areas=reply.data.areas.map(value=>({...value,clientIds:[...value.clientIds]}));
  const editor=form(view,t('focus.title'),t('ui.save'),async()=>{
    const result=await view.session.request('/focus-areas',{method:'PUT',etag:reply.etag,body:{areas}});
    if(!view.valid())return;
    reply.etag=result.etag;view.clean(editor.value);editor.feedback.textContent=t('ui.saved');await changed();
  });
  editor.value.id='focus-settings';
  const list=node('div');editor.value.append(list);
  const paint=()=>{
    list.replaceChildren();
    for(const area of areas){
      const group=node('fieldset',undefined,'mb-3'),legend=node('legend',t('focus.area'),'h6');group.append(legend);
      const name=field(group,t('ui.name'),area.name,{maxLength:80});name.disabled=!view.canWrite;
      name.addEventListener('input',()=>{area.name=name.value;});
      const members=node('fieldset'),caption=node('legend',t('ui.clients'),'h6');members.append(caption);group.append(members);
      // A removed client is no longer a selectable membership and cannot leak work.
      area.clientIds=area.clientIds.filter(id=>clients.items.some(client=>client.id===id));
      for(const client of clients.items){const choice=field(members,client.name,area.clientIds.includes(client.id),{type:'checkbox'});choice.disabled=!view.canWrite;
        choice.addEventListener('change',()=>{area.clientIds=choice.checked?[...area.clientIds,client.id]:area.clientIds.filter(id=>id!==client.id);});}
      const remove=button(t('focus.remove'),()=>{areas=areas.filter(value=>value!==area);view.dirty.add(editor.value);paint();});remove.disabled=!view.canWrite;group.append(remove);list.append(group);
    }
  };
  const add=button(t('focus.add'),()=>{if(areas.length>=100)return;areas.push({id:crypto.randomUUID(),name:'',clientIds:[]});view.dirty.add(editor.value);paint();list.lastElementChild?.querySelector('input')?.focus();});add.disabled=!view.canWrite;
  editor.value.append(add,node('p',t('focus.hint'),'hint'));paint();editor.finish();
}
