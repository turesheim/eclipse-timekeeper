import {test} from 'node:test';
import assert from 'node:assert/strict';
import {seasonForDay,dailyBannerProposal,capturedBanner} from '../src/daily-banner-presentation.js';
import {dailyBannerProperty,dailyBannerSnapshot} from '../src/daily-banner-model.js';

test('artwork follows the selected civil day and location hemisphere',()=>{
  assert.equal(seasonForDay('2026-11-16',63),'late-autumn');assert.equal(seasonForDay('2026-11-16',-34),'spring');
  assert.equal(seasonForDay('2026-02-28',0),'winter');assert.equal(seasonForDay('2026-02-28',-34),'summer');
  assert.equal(seasonForDay('2026-02-31'),null);assert.equal(dailyBannerProposal(null),null);
  const proposal=dailyBannerProposal({date:'2026-11-16',zoneId:'Europe/Oslo',weatherLocation:null});
  assert.equal(proposal.artwork.id,'late-autumn-cloudy');assert.deepEqual(proposal.forecast,[]);assert.equal(proposal.zoneId,'Europe/Oslo');
});
test('a captured forecast is portable while an unsaved proposal uses local artwork',()=>{
  const context={date:'2026-11-16',zoneId:'Europe/Oslo'},preview={version:1,date:context.date,zoneId:context.zoneId,season:'late-autumn',weather:'snow',forecast:['09:00','12:00','15:00'].map(time=>({time,condition:'snow',temperatureCelsius:-2})),provider:'MET Norway'};
  const proposal=dailyBannerProposal(context,preview),captured=capturedBanner(proposal,'journal/assets/weather.png');
  assert.equal(proposal.artwork.path,'/web/assets/seasonal/v1/late-autumn-snow.png');assert.equal(captured.artwork,undefined);
  const header='---\n'+dailyBannerProperty+': '+JSON.stringify(JSON.stringify(captured))+'\n---\n';
  assert.deepEqual(dailyBannerSnapshot(header),captured);assert.equal(captured.forecast.length,3);assert.ok(captured.forecast.every(slot=>slot.temperature.includes('2')));
  assert.equal(dailyBannerProposal(context,{...preview,date:'2026-11-17'}),null);
  assert.deepEqual(dailyBannerProposal(context,{...preview,forecast:preview.forecast.map(slot=>({...slot,temperatureCelsius:Infinity}))}).forecast,[]);
});
