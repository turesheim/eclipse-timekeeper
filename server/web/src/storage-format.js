export function storageRatio(used,limit){return limit>0?Math.max(0,Math.min(100,used/limit*100)):0;}
export function storageBytes(bytes,locale){
  const units=['B','kB','MB','GB','TB','PB'];let value=bytes,index=0;
  while(value>=1000&&index<units.length-1){value/=1000;index++;}
  return new Intl.NumberFormat(locale,{maximumFractionDigits:index?2:0}).format(value)+' '+units[index];
}
