import {test} from 'node:test';
import assert from 'node:assert/strict';
import {dailyBannerSnapshot,replaceBanner} from '../src/daily-banner-model.js';
import {bannerMetadata} from '../src/markdown-banner.js';
import {splitMarkdown} from '../src/markdown-document.js';
import {seasonalArtwork} from '../src/seasonal-banner-catalog.js';
const snapshot={version:1,date:'2026-11-16',zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:'Images/original.png',x:.25,y:.75,id:'late-autumn-snow',version:1},forecast:[9,12,15].map(hour=>({time:hour.toString().padStart(2,'0')+':00',condition:'snow',label:'Light snow',temperature:'−1 °C',temperatureCelsius:-1}))};

test('saved selection and forecast survive an override and restore without touching unrelated YAML or prose',()=>{
 const before='\uFEFF---\r\n# Keep this\r\nother: [one, two]\r\n---\r\n\r\nExact **body**.\r\n';
 const seasonal=replaceBanner(before,{...snapshot.seasonal,snapshot});
 const custom=replaceBanner(seasonal,{reference:'Images/custom.jpg',x:0,y:1});
 assert.deepEqual(dailyBannerSnapshot(splitMarkdown(custom).frontmatter),snapshot);
 assert.deepEqual(bannerMetadata(splitMarkdown(custom).frontmatter),{reference:'Images/custom.jpg',x:0,y:1});
 assert.equal(replaceBanner(custom,snapshot.seasonal),seasonal);
 assert.ok(custom.startsWith('\uFEFF---\r\n# Keep this\r\nother: [one, two]\r\n'));
 assert.equal(splitMarkdown(custom).body,splitMarkdown(before).body);
});
test('new metadata retains a body BOM and refuses ambiguous or structured owned values',()=>{
 const source='\uFEFFExact prose\r\n';const result=replaceBanner(source,{...snapshot.seasonal,snapshot});
 assert.ok(result.startsWith('\uFEFF---\r\n'));assert.equal(splitMarkdown(result).body,'Exact prose\r\n');
 for(const metadata of ['banner: "[[one.png]]"\n"banner": "[[two.png]]"','banner: |\n  multiline','banner:\n  nested: nope'])assert.throws(()=>replaceBanner('---\n'+metadata+'\n---\nbody',snapshot.seasonal));
 for(const reference of ['https://outside.invalid/a.png','../outside.png','Images/a.svg'])assert.throws(()=>replaceBanner('',{reference}));
});
test('invalid saved metadata never becomes an overlay or a remote image',()=>{
 const serialize=value=>splitMarkdown(replaceBanner('',{...snapshot.seasonal,snapshot:value})).frontmatter;
 assert.equal(dailyBannerSnapshot(serialize({...snapshot,version:2})),null);
 assert.equal(dailyBannerSnapshot(serialize({...snapshot,forecast:snapshot.forecast.slice(0,1)})),null);
 assert.equal(dailyBannerSnapshot(serialize({...snapshot,seasonal:{...snapshot.seasonal,reference:'https://outside.invalid/a.png'}})),null);
 assert.equal(dailyBannerSnapshot(serialize({...snapshot,zoneId:'invalid-zone'})),null);
});
test('catalog chooses seasonal weather artwork with a seasonal-only fallback',()=>{
 assert.equal(seasonalArtwork('late-autumn','snow').id,'late-autumn-snow');
 assert.equal(seasonalArtwork('summer','rain').id,'summer-rain');
 assert.equal(seasonalArtwork('spring','unavailable').season,'spring');
});
