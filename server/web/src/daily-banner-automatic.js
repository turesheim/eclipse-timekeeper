import {t} from './i18n.js';
import {splitMarkdown} from './markdown-document.js';
import {bannerMetadata} from './markdown-banner.js';
import {dailyBannerSnapshot,replaceBanner} from './daily-banner-model.js';
import {dailyBannerProposal,capturedBanner} from './daily-banner-presentation.js';

const contextKey=(context,resources)=>JSON.stringify([context.date,context.zoneId,context.weatherLocation,!!resources.canCaptureBanner?.()]);
const stale=()=>Object.assign(new Error(t('notes.stale')),{status:412});

/** Weather and uploads never lock typing; only the guarded metadata write joins autosave. */
export function automaticDailyBanner(f){
  const resources=f.resources;let attempt;
  f.clearAutomaticBanner=()=>{attempt?.abort?.abort();attempt=null;};
  const active=state=>state===attempt&&state.generation===f.generation&&state.session===resources.bannerIdentity?.().session&&contextKey(resources.dailyBannerContext?.()||{},resources)===state.key;
  const capture=async state=>{
    const current=resources.dailyBannerContext?.(),saved=f.banner.snapshot;
    if(!active(state)||!current?.present||f.readOnly||f.loading||!resources.canCaptureBanner?.()||!resources.serializeBanner||state.capturing||state.captureFailed||state.captured||saved?.forecast.length===3||saved&&!state.proposal.forecast.length)return;
    state.capturing=true;
    try{
      let context=await resources.readBanner({flush:false});if(!active(state))return;
      const parts=splitMarkdown(context.text),previous=dailyBannerSnapshot(parts.frontmatter);
      // Imported or future-version presentation properties remain opaque.
      const declares=key=>new RegExp('^(?:'+key+'|"'+key+'"|\''+key+'\')[ \t]*:','m').test(parts.frontmatter);
      if(parts.unterminated||declares('banner')&&!bannerMetadata(parts.frontmatter)||declares('dagsverk_seasonal_banner')&&!previous){state.captured=true;return;}
      if(previous?.forecast.length===3){state.captured=true;return;}
      let reference=previous?.seasonal.reference;
      if(!reference){
        const response=await fetch(state.proposal.artwork.path,{credentials:'same-origin'});if(!response.ok)throw new Error(t('daily_banner.failed'));
        const bytes=new Uint8Array(await response.arrayBuffer());if(!active(state))return;
        reference=await resources.uploadBanner(context,bytes,'png');if(!active(state))return;
      }
      if(f.loading||!resources.canCaptureBanner?.())return;
      const success=await resources.serializeBanner(async()=>{
        if(!active(state)||f.readOnly||f.loading)return false;
        try{
          context=await resources.refreshBanner(context);if(!active(state))return false;
          if(!resources.bannerMatches?.(context))throw stale();
          const parts=splitMarkdown(context.text),stored=dailyBannerSnapshot(parts.frontmatter);
          if(parts.unterminated){state.captureFailed=true;return false;}
          if(stored?.forecast.length===3){state.captured=true;return true;}
          const snapshot=stored?{...stored,forecast:state.proposal.forecast,provider:state.proposal.provider,capturedAt:state.proposal.capturedAt}:capturedBanner(state.proposal,reference);
          const text=replaceBanner(context.text,{...(bannerMetadata(parts.frontmatter)||snapshot.seasonal),snapshot});
          if(text===context.text){state.captured=true;return true;}
          const reply=await resources.writeBanner(context,text,{notify:false});if(!active(state))return false;
          await resources.bannerCommitted(context,text,reply);state.captured=true;return true;
        }catch(error){if(active(state)){state.captureFailed=true;resources.bannerFailed?.(error);if(active(state))f.status.textContent=error.message||t('daily_banner.failed');}return false;}
      });
      if(active(state)&&success)resources.announceBanner?.();
    }catch(error){if(active(state)){state.captureFailed=true;f.status.textContent=error.message||t('daily_banner.failed');}}
    finally{state.capturing=false;}
  };
  f.syncAutomaticBanner=()=>{
    const context=resources.dailyBannerContext?.();
    if(!context?.date||!f.banner.metadataReady||f.rich.hidden||f.loading||!dailyBannerProposal(context))return;
    if(f.banner.snapshot?.forecast.length===3)return;
    const key=contextKey(context,resources),session=resources.bannerIdentity?.().session;
    if(attempt?.key===key&&attempt.generation===f.generation&&attempt.session===session){if(attempt.ready)void capture(attempt);return;}
    attempt?.abort?.abort();
    const state=attempt={key,session,generation:f.generation,context,proposal:dailyBannerProposal(context),ready:!context.weatherLocation,abort:new AbortController()};
    f.banner.setProposal(context,null,{pending:!!context.weatherLocation});
    if(!context.weatherLocation){void capture(state);return;}
    void resources.bannerPreview(context.date,{signal:state.abort.signal}).then(preview=>{
      if(!active(state))return;state.proposal=dailyBannerProposal(context,preview);if(!state.proposal)throw new Error(t('daily_banner.forecast_unavailable'));
      state.ready=true;f.banner.setProposal(context,preview);void capture(state);
    }).catch(()=>{if(active(state)){state.ready=false;f.banner.setProposal(context,null,{failed:true});}});
  };
  f.banner.dom.addEventListener('timekeeper:banner-retry',()=>{f.clearAutomaticBanner();f.syncAutomaticBanner();});
}
