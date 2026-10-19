// Applied before styles load so a saved dark theme does not flash a light page.
(() => {
  const key='timekeeper.theme';
  const themes=[['timekeeper','Timekeeper','light'],['timekeeper-dark','Timekeeper Dark','dark'],
    ['solarized-light','Solarized Light','light'],['solarized-dark','Solarized Dark','dark'],
    ['nord','Nord','dark'],['dracula','Dracula','dark']];
  const normalize=value=>themes.some(([id])=>id===value) ? value:'timekeeper';
  let selected='timekeeper',picker;
  const apply=value=>{
    selected=normalize(value);document.documentElement.dataset.theme=selected;
    document.documentElement.dataset.bsTheme=themes.find(([id])=>id===selected)[2];
    if(picker)picker.value=selected;
  };
  try {selected=normalize(localStorage.getItem(key));} catch { /* Browser storage is optional. */ }
  apply(selected);
  document.addEventListener('DOMContentLoaded',()=>{
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
