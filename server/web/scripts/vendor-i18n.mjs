import {readFile,writeFile,mkdir} from 'node:fs/promises';
const upstream=new URL('../node_modules/i18next/',import.meta.url);
const assets=new URL('../src/assets/i18next/',import.meta.url);
const locales=new URL('../src/locales/',import.meta.url);
const check=process.argv.includes('--check');
const {version}=JSON.parse(await readFile(new URL('package.json',upstream)));
if(version!=='26.5.2')throw new Error('Expected i18next 26.5.2');
const resources={};
for(const language of ['en','nb'])resources[language]={translation:JSON.parse(await readFile(new URL(language+'.json',locales)))};
const keys=Object.keys(resources.en.translation).sort();
if(JSON.stringify(keys)!==JSON.stringify(Object.keys(resources.nb.translation).sort()))throw new Error('English and Norwegian translation keys differ');
const variables=value=>[...value.matchAll(/\{\{\s*([^}]+?)\s*\}\}/g)].map(match=>match[1]).sort();
for(const key of keys) {
  const english=resources.en.translation[key],norwegian=resources.nb.translation[key];
  if(typeof english!=='string'||!english.trim()||typeof norwegian!=='string'||!norwegian.trim())throw new Error('Missing translation: '+key);
  if(JSON.stringify(variables(english))!==JSON.stringify(variables(norwegian)))throw new Error('Interpolation variables differ: '+key);
}
const files=[
  [new URL('i18next.js',assets),await readFile(new URL('dist/esm/i18next.js',upstream))],
  [new URL('LICENSE',assets),await readFile(new URL('LICENSE',upstream))],
  [new URL('README.md',assets),`# i18next\n\nPinned source: \`i18next@${version}\` from the official npm package.\nThe unmodified ES module and MIT license ship with the server for offline use.\nRegenerate with \`npm run vendor:i18n\` in \`server/web\`.\n`],
  [new URL('resources.js',locales),'// Generated from en.json and nb.json by scripts/vendor-i18n.mjs.\nexport const resources='+JSON.stringify(resources)+';\n']
];
for(const [file,content] of files) {
  if(check){if(!Buffer.from(await readFile(file)).equals(Buffer.from(content)))throw new Error('Regenerate localization asset: '+file.pathname);}
  else {await mkdir(new URL('.',file),{recursive:true});await writeFile(file,content);}
}
console.log(`${check?'Verified':'Bundled'} i18next ${version} and ${keys.length} translations in English and Norwegian Bokmål.`);
