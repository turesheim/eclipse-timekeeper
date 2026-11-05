import {t,getLocale} from '/web/i18n.js';
import {node,form,field,passwordField,section,table} from '/web/account-ui.js';
import {storageBytes} from '/web/storage-format.js';

export async function renderNotesLimits(view){
  let loaded=await view.session.request('/admin/notes/limits');if(!view.valid())return;
  const status=section(view,t('storage.admin_title')),summary=node('div');status.append(node('p',t('storage.admin_help'),'hint'),summary);
  const describe=()=>{const limits=loaded.data.limits,bytes=value=>storageBytes(value,getLocale());summary.replaceChildren(table([t('ui.setting'),t('storage.current_limit')],[
    [t('storage.total'),bytes(limits.totalBytes)],[t('storage.entries'),new Intl.NumberFormat(getLocale()).format(limits.entries)],
    [t('storage.markdown_limit'),bytes(limits.markdownBytes)],[t('storage.attachment_limit'),bytes(limits.fileBytes)]
  ]));};describe();
  if(!loaded.data.canEdit){status.append(node('p',t('ui.sign_in_with_your_account_password_or_a_passkey_to_change_service_settings_an_api_tok'),'alert alert-info'));return;}
  const editor=form(view,t('storage.edit_limits'),t('ui.save_settings'),async({value,feedback})=>{
    const limits={};for(const [name,{input,scale}]of Object.entries(fields)){const number=Number(input.value)*scale;limits[name]=Math.round(number);if(!Number.isFinite(number)||!Number.isSafeInteger(limits[name]))throw new Error(t('storage.invalid_limits'));}
    if(limits.fileBytes<1||limits.markdownBytes<1||limits.markdownBytes>limits.fileBytes||limits.totalBytes<limits.fileBytes||limits.entries<4)throw new Error(t('storage.invalid_limits'));
    const reply=await view.session.request('/admin/notes/limits',{method:'PUT',etag:loaded.etag,body:{limits,currentPassword:proof.value}});if(!view.valid())return;
    loaded=reply;proof.value='';view.clean(value);describe();feedback.textContent=t('storage.saved');
  });
  const fields={};
  for(const [name,label,scale,min,max]of [
    ['totalBytes','storage.total_gb',1_000_000_000,1/1_000_000_000,9007199254740991/1_000_000_000],
    ['entries','storage.entries',1,4,1_000_000],
    ['markdownBytes','storage.markdown_mb',1_000_000,1/1_000_000,Math.max(loaded.data.limits.fileBytes,loaded.data.maximumUploadBytes)/1_000_000],
    ['fileBytes','storage.attachment_mb',1_000_000,1/1_000_000,Math.max(loaded.data.limits.fileBytes,loaded.data.maximumUploadBytes)/1_000_000]
  ]){
    const input=field(editor.value,t(label),loaded.data.limits[name]/scale,{type:'number'});input.min=String(min);input.max=String(max);input.step=String(1/scale);fields[name]={input,scale};
  }
  editor.value.append(node('p',t('storage.upload_ceiling',{limit:storageBytes(loaded.data.maximumUploadBytes,getLocale())}),'hint'));
  const proof=passwordField(editor.value,t('ui.current_administrator_password'),true);proof.autocomplete='section-notes-limits current-password';
  view.cleanup.add(()=>{proof.value='';});editor.finish();
}
