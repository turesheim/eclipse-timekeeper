import {ApiError} from './api.js';

export function authorizationTarget(provider,address,sharedOrigin){
  try{
    const target=new URL(address),paths={google:'/o/oauth2/v2/auth',microsoft:'/common/oauth2/v2.0/authorize'},hosts={google:'accounts.google.com',microsoft:'login.microsoftonline.com'};
    if(sharedOrigin){
      const origin=new URL(sharedOrigin);if(origin.protocol!=='https:'||origin.origin!==sharedOrigin||target.origin!==sharedOrigin||!/^\/connect\/[A-Za-z0-9_-]{43}$/.test(target.pathname)||target.search||target.hash||target.username||target.password)throw new Error();return target.href;
    }
    if(target.protocol!=='https:'||target.hostname!==hosts[provider]||target.pathname!==paths[provider]||target.username||target.password||target.port||target.hash)throw new Error();
    return target.href;
  }catch{throw new ApiError('UNAVAILABLE');}
}
