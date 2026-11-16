// Immutable original Dagsverk illustrations. A changed illustration gets a new catalog version.
export const seasonalCatalog=Object.freeze([
  {id:'winter-snow',season:'winter',weather:['snow','sleet','cloudy'],file:'winter-snow.png'},
  {id:'spring-clear',season:'spring',weather:['clear','fair','partlycloudy'],file:'spring-clear.png'},
  {id:'spring-rain',season:'spring',weather:['rain','thunder','cloudy','fog'],file:'spring-rain.png'},
  {id:'summer-clear',season:'summer',weather:['clear','fair','partlycloudy'],file:'summer-clear.png'},
  {id:'summer-rain',season:'summer',weather:['rain','thunder','cloudy','fog'],file:'summer-rain.png'},
  {id:'autumn-clear',season:'autumn',weather:['clear','fair','partlycloudy'],file:'autumn-clear.png'},
  {id:'autumn-rain',season:'autumn',weather:['rain','thunder','cloudy','fog'],file:'autumn-rain.png'},
  {id:'late-autumn-snow',season:'late-autumn',weather:['snow','sleet'],file:'late-autumn-snow.png'},
  {id:'late-autumn-cloudy',season:'late-autumn',weather:['clear','fair','partlycloudy','rain','thunder','cloudy','fog'],file:'late-autumn-cloudy.png'}
].map(value=>Object.freeze({...value,version:1,path:'/web/assets/seasonal/v1/'+value.file,x:.5,y:.5})));
export function seasonalArtwork(season,weather){return seasonalCatalog.find(value=>value.season===season&&value.weather.includes(weather))||seasonalCatalog.find(value=>value.season===season)||seasonalCatalog[0];}
