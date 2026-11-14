import {t} from '/web/i18n.js';
import {node,section,table,button,link,form,field,passwordField} from '/web/account-ui.js';

export async function renderInternetAccounts(view){
  let loaded=await view.session.request('/admin/internet-accounts');if(!view.valid())return;
  let formEtag=loaded.etag;
  const status=section(view,t('task_providers.title')),feedback=node('p'),pending=node('p','','hint'),summary=node('div');feedback.setAttribute('role','status');pending.setAttribute('role','status');
  const check=button(t('internet_admin.check'),()=>void view.run(async()=>{
    const reply=await view.session.request('/admin/internet-accounts?check=true',{timeout:20000});if(!view.valid())return;loaded=reply;paint();
  },{submit:check}));
  status.append(node('p',t('internet_admin.help'),'hint'),node('p',t('internet_admin.not_sso'),'hint'),feedback,pending,summary,check,' ',link(t('internet_admin.public_address_action'),'#settings'));
  const configured=value=>t(value===null?'internet_admin.not_checked':value?'ui.configured':'ui.not_configured');
  function paint(){
    const data=loaded.data;feedback.textContent=t('internet_admin.status_'+data.status.toLowerCase());feedback.className=['PUBLIC_ORIGIN_REQUIRED','CALLBACK_INVALID','REGISTRATION_REQUIRED','UNAVAILABLE'].includes(data.status)?'alert alert-warning':'hint';
    pending.textContent=t(data.restartRequired?'internet_admin.restart_required':'internet_admin.active');
    summary.replaceChildren(table([t('ui.setting'),t('internet_admin.current')],[
      [t('internet_admin.active_mode'),t(data.mode==='SHARED'?'internet_admin.shared':'internet_admin.direct')],
      [t('ui.public_https_address'),data.publicOrigin||t('ui.not_configured')],
      [t('internet_admin.return_address'),data.callbackUrl||t('ui.not_configured')],
      [t('internet_admin.service'),data.mode==='DIRECT'?t('internet_admin.not_used'):data.serviceUrl||t('internet_admin.project_discovery')],
      [t('internet_admin.google_redirect'),data.googleRedirectUrl||t('internet_admin.check_for_address')],
      [t('internet_admin.microsoft_redirect'),data.mode==='DIRECT'?data.callbackUrl||t('ui.not_configured'):data.serviceUrl?data.serviceUrl+'/oauth/microsoft/callback':t('internet_admin.check_for_address')],
      [t('internet_admin.google_registration'),configured(data.googleConfigured)],
      [t('internet_admin.microsoft_registration'),configured(data.microsoftConfigured)]
    ]));
  }
  paint();
  if(loaded.data.canEdit){
    const registrations=new Map();
    const editor=form(view,t('internet_admin.edit'),t('ui.save_settings'),async({value,feedback})=>{
      const body={mode:mode.value,serviceUrl:service.value.trim()||null,currentPassword:proof.value};
      for(const [provider,fields]of registrations){
        if(fields.clear.checked&&fields.secret.value)throw new Error(t('internet_admin.secret_choice'));
        body[provider+'ClientId']=fields.id.value.trim();body[provider+'SecretAction']=fields.clear.checked?'CLEAR':fields.secret.value?'REPLACE':'PRESERVE';
        if(fields.secret.value)body[provider+'ClientSecret']=fields.secret.value;
      }
      const reply=await view.session.request('/admin/internet-accounts',{method:'PUT',etag:formEtag,body});if(!view.valid())return;
      loaded=reply;formEtag=reply.etag;proof.value='';
      for(const [provider,fields]of registrations){fields.secret.value='';fields.clear.checked=false;fields.status.textContent=t(loaded.data.saved[provider+'ClientSecretConfigured']?'internet_admin.secret_saved':'internet_admin.secret_missing');}
      view.clean(value);paint();feedback.textContent=t(loaded.data.restartRequired?'internet_admin.restart_required':'internet_admin.active');
    });
    editor.value.append(node('p',t('internet_admin.edit_help'),'hint'));
    const saved=loaded.data.saved,mode=field(editor.value,t('internet_admin.mode'),saved.mode,{choices:[['SHARED',t('internet_admin.shared')],['DIRECT',t('internet_admin.direct')]]});
    const help=node('p','','alert alert-info');editor.value.append(help);
    const managed=node('div');editor.value.append(managed);const service=field(managed,t('internet_admin.service_override'),saved.serviceUrl,{type:'url',required:false,maxLength:2048});
    const direct=node('div');editor.value.append(direct);
    for(const provider of ['google','microsoft']){
      const group=node('fieldset');group.append(node('legend',t('internet_accounts.'+provider),'h5'));direct.append(group);
      const id=field(group,t('internet_admin.provider_client_id',{provider:t('internet_accounts.'+provider)}),saved[provider+'ClientId']||'',{required:false,maxLength:1024});
      const callback=field(group,t('internet_admin.direct_callback'),loaded.data.callbackUrl,{required:false});callback.readOnly=true;
      const status=node('p',t(saved[provider+'ClientSecretConfigured']?'internet_admin.secret_saved':'internet_admin.secret_missing'),'hint');group.append(status);
      const secret=field(group,t('internet_admin.provider_new_secret',{provider:t('internet_accounts.'+provider)}),'',{type:'password',required:false,autocomplete:'section-'+provider+' new-password',maxLength:16384});
      const clear=field(group,t('internet_admin.provider_clear_secret',{provider:t('internet_accounts.'+provider)}),false,{type:'checkbox'});registrations.set(provider,{id,secret,clear,status});
      view.cleanup.add(()=>{secret.value='';});
    }
    const updateMode=()=>{const shared=mode.value==='SHARED';help.textContent=t(shared?'internet_admin.managed_help':'internet_admin.self_managed_help');managed.hidden=!shared;direct.hidden=shared;};mode.addEventListener('change',updateMode);updateMode();
    const proof=passwordField(editor.value,t('ui.current_administrator_password'),true);proof.autocomplete='section-internet-accounts current-password';
    view.cleanup.add(()=>{proof.value='';});editor.finish();
  }else status.append(node('p',t('ui.sign_in_with_your_account_password_or_a_passkey_to_change_service_settings_an_api_tok'),'alert alert-info'));
  const guide=section(view,t('internet_admin.google_setup'));
  for(const mode of ['managed','direct']){
    guide.append(node('h3',t(mode==='managed'?'internet_admin.shared':'internet_admin.direct'),'h5'),node('p',t('internet_admin.'+(mode==='managed'?'managed_help':'self_managed_help')),'hint'));
    guide.append(link(t('internet_admin.guide'),`https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/operations/SHARED-SIGN-IN.md#${mode}`));
  }
  guide.append(node('h3',t('internet_accounts.google'),'h5'),node('p',t('internet_admin.step_google_project'),'hint'));
  const googleLinks=node('p');
  googleLinks.append(link(t('internet_admin.google_console'),'https://console.cloud.google.com/auth/clients'),' · ',
    link('Google Calendar API','https://console.cloud.google.com/apis/library/calendar-json.googleapis.com'),' · ',
    link('Google Tasks API','https://console.cloud.google.com/apis/library/tasks.googleapis.com'));
  guide.append(googleLinks,node('h3',t('internet_accounts.microsoft'),'h5'),
    link(t('internet_admin.microsoft_console'),'https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade'));
  guide.append(node('p',t('internet_admin.microsoft_accounts'),'hint'),node('p',t('internet_admin.live_check'),'hint'));
}
