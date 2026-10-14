import {ownerHeader} from './api.js';
const $ = id => document.getElementById(id);
let generation = 0;
const node = (tag,text) => {const value=document.createElement(tag);if(text!==undefined) value.textContent=text;return value;};
export function closeAccount() {++generation;$('account-dialog').close();$('account-content').replaceChildren();$('account-error').textContent='';}
$('account-close').addEventListener('click',closeAccount);
$('account-dialog').addEventListener('cancel',event=>{event.preventDefault();closeAccount();});
export async function openAccount(session,signOut) {
  const current=++generation, root=$('account-content');root.replaceChildren();$('account-error').textContent='';$('account-dialog').showModal();
  const valid=()=>current===generation;
  const run=async work=>{if(!valid()) return;$('account-error').textContent='';try {await work();}catch(error){if(valid()) $('account-error').textContent=error.message;}};
  function field(form,label,value='',type='text') {
    const wrapper=node('label',label),input=node('input');input.type=type;
    if(type==='checkbox') {input.checked=!!value;wrapper.className='check';} else {input.value=value;input.required=true;}
    if(type==='password') {input.autocomplete='new-password';input.minLength=15;}wrapper.append(input);form.append(wrapper);return input;
  }
  function form(title,submit,work) {
    const section=node('section'),heading=node('h3',title),value=node('form'),button=node('button',submit);button.type='submit';section.append(heading,value);root.append(section);
    value.addEventListener('submit',event=>{event.preventDefault();button.disabled=true;void run(work).finally(()=>{button.disabled=false;});});
    return {value,finish:()=>value.append(button)};
  }
  await run(async()=>{
    const loaded=await session.request('/auth/account');if(!valid()) return;
    const account=loaded.data;
    root.append(node('p',`Signed in as ${account.displayName}. Your records are private to this account.`));
    if(account.state==='UNCLAIMED') root.append(node('p','Ask the server operator for an account recovery code to add a username and password to this existing account.'));
    else {
      const profile=form('Profile','Save profile',async()=>{const reply=await session.request('/auth/account',{method:'PUT',etag:loaded.etag,body:{username:username.value,displayName:display.value}});loaded.etag=reply.etag;if(valid()) $('owner').textContent=reply.data.displayName;});
      const username=field(profile.value,'Account username',account.username),display=field(profile.value,'Account display name',account.displayName);username.autocomplete='username';profile.finish();
      const password=form('Change password','Change password',async()=>{await session.request('/auth/password',{method:'POST',body:{currentPassword:old.value,newPassword:next.value}});if(valid()) signOut('Password changed. Sign in again. Existing sessions and API tokens have been revoked.');});
      const old=field(password.value,'Current password','','password'),next=field(password.value,'New password','','password');old.autocomplete='current-password';old.minLength=0;password.finish();
    }
    const tokenSection=node('section');tokenSection.append(node('h3','Personal API tokens'),node('p','Use a separate named token for each Eclipse installation. New tokens are shown once.'));const list=node('div');tokenSection.append(list);root.append(tokenSection);
    async function tokens() {
      const {data}=await session.request('/auth/tokens');if(!valid()) return;list.replaceChildren();
      for(const token of data.items) {
        const row=node('p',`${token.name} · ${token.permissions.join(', ')} · ${token.revoked ? 'Revoked' : token.expiresAt ? 'Expires '+new Date(token.expiresAt).toLocaleString() : 'No expiry'}`);
        if(!token.revoked) {const revoke=node('button','Revoke');revoke.type='button';revoke.addEventListener('click',()=>void run(async()=>{await session.request(`/auth/tokens/${token.id}`,{method:'DELETE'});await tokens();}));row.append(' ',revoke);}list.append(row);
      }
    }
    await tokens();if(!valid()) return;
    const tokenForm=form('Create API token','Create token',async()=>{
      const expiresAt=expiry.value ? new Date(expiry.value).toISOString() : null;
      const {data}=await session.request('/auth/tokens',{method:'POST',body:{name:name.value,permissions:write.checked ? ['read','write']:['read'],expiresAt}});if(!valid()) return;
      secret.replaceChildren(node('p','Copy this token now. It will not be shown again.'));const value=node('input');value.readOnly=true;value.value=data.token;value.setAttribute('aria-label','New API token');secret.append(value);name.value='';await tokens();
    });
    const name=field(tokenForm.value,'Token name'),expiry=field(tokenForm.value,'Token expiry (local time, optional)','','datetime-local'),write=field(tokenForm.value,'Allow changes',true,'checkbox');expiry.required=false;tokenForm.finish();const secret=node('div');root.append(secret);
    if(account.administrator) {
      const heading=node('h3','User accounts');root.append(heading,node('p','Administrators manage account access. They cannot read another account’s work records.'));
      const users=node('div');root.append(users);
      async function loadUsers() {
        const {data}=await session.request('/auth/users');if(!valid()) return;users.replaceChildren();
        for(const user of data.users) {
          const section=node('details');section.append(node('summary',`${user.displayName} (${user.username ?? 'Unclaimed'}) · ${user.state}`));users.append(section);
          if(user.state==='UNCLAIMED') {section.append(node('p','The operator must issue an explicit owner-bound recovery code to claim this account.'));continue;}
          const form=node('form'),username=field(form,'User username',user.username),display=field(form,'User display name',user.displayName),enabled=field(form,'Enabled',user.state==='ENABLED','checkbox'),admin=field(form,'Administrator',user.administrator,'checkbox'),save=node('button','Save user');save.type='submit';form.append(save);section.append(form);
          form.addEventListener('submit',event=>{event.preventDefault();save.disabled=true;void run(async()=>{
            const path=`/auth/users/${ownerHeader(user.ownerId)}`,loaded=await session.request(path);
            // The values rendered above must still match the account version being edited.
            if(loaded.data.user.version!==user.version) throw new Error('This account changed. Close and reopen Account before editing it.');
            await session.request(path,{method:'PUT',etag:loaded.etag,body:{username:username.value,displayName:display.value,enabled:enabled.checked,administrator:admin.checked}});
            if(user.ownerId===account.ownerId && (!enabled.checked || !admin.checked)) {if(valid()) signOut('Account access changed. Sign in again.');return;}
            await loadUsers();
          }).finally(()=>{save.disabled=false;});});
        }
      }
      await loadUsers();if(!valid()) return;
      const create=form('Create user account','Create user',async()=>{await session.request('/auth/users',{method:'POST',body:{username:username.value,password:password.value,displayName:display.value,administrator:admin.checked}});password.value='';username.value='';display.value='';await loadUsers();});
      const username=field(create.value,'New user username'),display=field(create.value,'New user display name'),password=field(create.value,'Temporary password','','password'),admin=field(create.value,'New user is administrator',false,'checkbox');create.finish();
    }
  });
}
