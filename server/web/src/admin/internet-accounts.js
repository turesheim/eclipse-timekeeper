import {t} from '/web/i18n.js';
import {node,section,table,button,link,form,field,passwordField} from '/web/account-ui.js';

export async function renderInternetAccounts(view){
  let loaded=await view.session.request('/admin/internet-accounts');if(!view.valid())return;
  let formEtag=loaded.etag;
  const status=section(view,t('task_providers.title')),feedback=node('p'),pending=node('p','','hint'),summary=node('div');feedback.setAttribute('role','status');pending.setAttribute('role','status');
  const check=button(t('internet_admin.check'),()=>void view.run(async()=>{
    const reply=await view.session.request('/admin/internet-accounts?check=true',{timeout:60000});if(!view.valid())return;loaded=reply;paint();
  },{submit:check}));
  status.append(node('p',t('internet_admin.help'),'hint'),feedback,pending,summary,check,' ',link(t('internet_admin.public_address_action'),'#settings'));
  const configured=value=>t(value===null?'internet_admin.not_checked':value?'ui.configured':'ui.not_configured');
  function paint(){
    const data=loaded.data;feedback.textContent=t('internet_admin.status_'+data.status.toLowerCase());feedback.className=['PUBLIC_ORIGIN_REQUIRED','UNAVAILABLE'].includes(data.status)?'alert alert-warning':'hint';
    pending.textContent=t(data.restartRequired?'internet_admin.restart_required':'internet_admin.active');
    summary.replaceChildren(table([t('ui.setting'),t('internet_admin.current')],[
      [t('internet_admin.mode'),t(data.mode==='SHARED'?'internet_admin.shared':'internet_admin.direct')],
      [t('ui.public_https_address'),data.publicOrigin||t('ui.not_configured')],
      [t('internet_admin.return_address'),data.callbackUrl||t('ui.not_configured')],
      [t('internet_admin.service'),data.serviceUrl||t('internet_admin.project_discovery')],
      [t('internet_admin.google_redirect'),data.googleRedirectUrl||t('internet_admin.check_for_address')],
      [t('internet_admin.google_registration'),configured(data.googleConfigured)],
      [t('internet_admin.microsoft_registration'),configured(data.microsoftConfigured)]
    ]));
  }
  paint();
  if(loaded.data.canEdit){
    const editor=form(view,t('internet_admin.edit'),t('ui.save_settings'),async({value,feedback})=>{
      if(clear.checked&&secret.value)throw new Error(t('internet_admin.secret_choice'));
      const reply=await view.session.request('/admin/internet-accounts',{method:'PUT',etag:formEtag,body:{
        mode:mode.value,serviceUrl:service.value.trim()||null,googleClientId:id.value.trim(),googleSecretAction:clear.checked?'CLEAR':secret.value?'REPLACE':'PRESERVE',
        currentPassword:proof.value,...(secret.value?{googleClientSecret:secret.value}:{})
      }});if(!view.valid())return;
      loaded=reply;formEtag=reply.etag;secret.value='';proof.value='';clear.checked=false;view.clean(value);paint();secretStatus.textContent=t(loaded.data.saved.googleClientSecretConfigured?'internet_admin.secret_saved':'internet_admin.secret_missing');feedback.textContent=t(loaded.data.restartRequired?'internet_admin.restart_required':'internet_admin.active');
    });
    editor.value.append(node('p',t('internet_admin.edit_help'),'hint'));
    const saved=loaded.data.saved,mode=field(editor.value,t('internet_admin.mode'),saved.mode,{choices:[['SHARED',t('internet_admin.shared')],['DIRECT',t('internet_admin.direct')]]});
    const service=field(editor.value,t('internet_admin.service_override'),saved.serviceUrl,{type:'url',required:false,maxLength:2048});
    const id=field(editor.value,t('internet_admin.client_id'),saved.googleClientId,{required:false,maxLength:1024});
    const callback=field(editor.value,t('internet_admin.direct_callback'),loaded.data.callbackUrl,{required:false});callback.readOnly=true;
    const secretStatus=node('p',t(saved.googleClientSecretConfigured?'internet_admin.secret_saved':'internet_admin.secret_missing'),'hint');editor.value.append(secretStatus);
    const secret=field(editor.value,t('internet_admin.new_secret'),'',{type:'password',required:false,autocomplete:'section-google new-password',maxLength:16384});
    const clear=field(editor.value,t('internet_admin.clear_secret'),false,{type:'checkbox'});
    const proof=passwordField(editor.value,t('ui.current_administrator_password'),true);proof.autocomplete='section-internet-accounts current-password';
    view.cleanup.add(()=>{secret.value='';proof.value='';});editor.finish();
  }else status.append(node('p',t('ui.sign_in_with_your_account_password_or_a_passkey_to_change_service_settings_an_api_tok'),'alert alert-info'));
  const guide=section(view,t('internet_admin.google_setup'));guide.append(node('p',t('internet_admin.credentials_help'),'hint'));
  const steps=node('ol',undefined,'text-break');for(const key of ['host','google_project','google_client','service_environment','application','user'])steps.append(node('li',t('internet_admin.step_'+key)));guide.append(steps);
  guide.append(link(t('internet_admin.guide'),'https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/operations/SHARED-SIGN-IN.md'),node('p',t('internet_admin.live_check'),'hint'));
  if(loaded.data.mode==='DIRECT')guide.append(node('p',t('internet_admin.direct_help'),'alert alert-info'));
}
