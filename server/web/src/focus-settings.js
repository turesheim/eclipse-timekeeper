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
  const list=node('div',undefined,'focus-area-list');editor.value.append(list);
  const dialog=node('dialog',undefined,'app-dialog focus-clients-dialog');dialog.setAttribute('aria-label',t('ui.clients'));
  const heading=node('h2',t('ui.clients')),search=node('input');search.type='search';search.className='form-control';search.setAttribute('aria-label',t('focus.search_clients'));
  const members=node('div',undefined,'focus-client-list'),actions=node('div',undefined,'focus-client-actions');
  let selectedArea=null,selection=new Set(),trigger=null;
  const paintMembers=()=>{
    members.replaceChildren();const query=search.value.trim().toLocaleLowerCase();
    for(const client of [...clients.items].sort((a,b)=>a.name.localeCompare(b.name))){
      if(query&&!client.name.toLocaleLowerCase().includes(query))continue;
      const label=node('label',undefined,'focus-client-choice'),choice=node('input');choice.type='checkbox';choice.className='form-check-input';choice.checked=selection.has(client.id);choice.disabled=!view.canWrite;
      choice.addEventListener('change',()=>choice.checked?selection.add(client.id):selection.delete(client.id));label.append(choice,node('span',client.name));members.append(label);
    }
    if(!members.children.length)members.append(node('p',t('focus.no_clients'),'hint'));
  };
  const close=()=>dialog.close();
  const cancel=button(t('ui.cancel'),close),apply=button(t('focus.apply'),()=>{
    if(!view.canWrite||!selectedArea)return;
    const next=[...selection];if(next.length!==selectedArea.clientIds.length||next.some(id=>!selectedArea.clientIds.includes(id))){selectedArea.clientIds=next;view.dirty.add(editor.value);trigger.textContent=t('focus.clients_count',{count:next.length});}
    close();
  },'btn btn-primary');apply.disabled=!view.canWrite;
  actions.append(cancel,apply);dialog.append(heading,search,members,actions);editor.value.append(dialog);
  for(const type of ['input','change'])dialog.addEventListener(type,event=>event.stopPropagation());
  dialog.addEventListener('close',()=>{selectedArea=null;trigger?.focus();});search.addEventListener('input',paintMembers);
  const paint=()=>{
    list.replaceChildren();
    for(const area of areas){
      const group=node('div',undefined,'focus-area-row');group.setAttribute('role','group');group.setAttribute('aria-label',t('focus.area'));
      const name=field(group,t('ui.name'),area.name,{maxLength:80});name.disabled=!view.canWrite;
      name.addEventListener('input',()=>{area.name=name.value;});
      area.clientIds=area.clientIds.filter(id=>clients.items.some(client=>client.id===id));
      const choose=button(t('focus.clients_count',{count:area.clientIds.length}),()=>{selectedArea=area;selection=new Set(area.clientIds);trigger=choose;search.value='';paintMembers();dialog.showModal();search.focus();});choose.setAttribute('aria-haspopup','dialog');
      const remove=button(t('focus.remove'),()=>{areas=areas.filter(value=>value!==area);view.dirty.add(editor.value);paint();});remove.disabled=!view.canWrite;group.append(choose,remove);list.append(group);
    }
  };
  const add=button(t('focus.add'),()=>{if(areas.length>=100)return;areas.push({id:crypto.randomUUID(),name:'',clientIds:[]});view.dirty.add(editor.value);paint();list.lastElementChild?.querySelector('input')?.focus();});add.disabled=!view.canWrite;
  editor.value.append(add,node('p',t('focus.hint'),'hint'));paint();editor.finish();
}
