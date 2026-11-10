// Applied before styles load so a saved dark theme does not flash a light page.
(() => {
  const key='timekeeper.theme';
  const themes=[['timekeeper','Dagsverk','light'],['timekeeper-dark','Dagsverk Dark','dark'],
    ['solarized-light','Solarized Light','light'],['solarized-dark','Solarized Dark','dark'],
    ['nord','Nord','dark'],['dracula','Dracula','dark']];
  const normalize=value=>themes.some(([id])=>id===value) ? value:'timekeeper';
  const branding=new URL('./assets/branding/',document.currentScript.src);
  let selected='timekeeper',picker,favicon,originalIcon;
  const apply=value=>{
    selected=normalize(value);document.documentElement.dataset.theme=selected;
    document.documentElement.dataset.bsTheme=themes.find(([id])=>id===selected)[2];
    if(picker)picker.value=selected;
    if(favicon)favicon.href=selected==='timekeeper' ? new URL('dagsverk-symbol.svg',branding) :
      selected==='timekeeper-dark' ? new URL('dagsverk-symbol-dark.svg',branding) : originalIcon;
  };
  try {selected=normalize(localStorage.getItem(key));} catch { /* Browser storage is optional. */ }
  apply(selected);
  document.addEventListener('DOMContentLoaded',()=>{
    favicon=document.querySelector('link[rel=icon]');originalIcon=favicon?.href;apply(selected);
    picker=document.getElementById('theme');if(!picker)return;
    for(const [id,label] of themes) {const option=document.createElement('option');option.value=id;option.textContent=label;option.dataset.i18n='theme.'+id;picker.append(option);}
    window.dispatchEvent(new Event('timekeeper-theme-options'));
    picker.value=selected;
    picker.addEventListener('change',()=>{
      apply(picker.value);
      try {localStorage.setItem(key,selected);} catch { /* Keep the chosen theme for this page. */ }
    });
  });
  window.addEventListener('storage',event=>{if(event.key===key || event.key===null)apply(event.key===null ? null:event.newValue);});
})();
