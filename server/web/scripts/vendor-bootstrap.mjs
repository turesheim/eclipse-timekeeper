// Regenerate CSS and the opt-in Tooltip component from the exact npm lockfile.
import {build} from 'esbuild';
import {readFile, writeFile, mkdir, readdir, rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const source = new URL('../node_modules/bootstrap/', import.meta.url);
const target = new URL('../src/assets/bootstrap/', import.meta.url);
const check = process.argv.includes('--check');
const {version} = JSON.parse(await readFile(new URL('package.json', source)));
if (version !== '5.3.8') throw new Error('Expected Bootstrap 5.3.8');
if (!check) {
  await mkdir(target, {recursive:true});
  for (const name of await readdir(target)) if (name.endsWith('.svg')) await rm(new URL(name,target));
}
let css = await readFile(new URL('dist/css/bootstrap.min.css', source), 'utf8');
const images = new Map();
css = css.replace(/url\("data:image\/svg\+xml,([^"\n]+)"\)/g, (_, data) => {
  const svg = decodeURIComponent(data);
  const name = `image-${createHash('sha256').update(svg).digest('hex').slice(0,12)}.svg`;
  images.set(name,svg);
  return `url("./${name}")`;
}).replace(/\/\*# sourceMappingURL=.*?\*\//g, '');
if (/data:|https?:\/\//.test(css.replace(/\/\*[\s\S]*?\*\//g,''))) throw new Error('Unexpected external or embedded asset');
const files = new Map([['bootstrap.min.css',css],['LICENSE',await readFile(new URL('LICENSE',source),'utf8')],...images]);
const bundle=await build({absWorkingDir:new URL('../',import.meta.url).pathname,entryPoints:['bootstrap/js/dist/tooltip.js'],bundle:true,format:'esm',target:['es2022'],minify:true,legalComments:'eof',write:false,metafile:true});
const dependencies=Object.keys(bundle.metafile.inputs);
if(dependencies.some(path=>!path.startsWith('node_modules/bootstrap/')&&!path.startsWith('node_modules/@popperjs/core/')))throw new Error('Unexpected tooltip dependency');
files.set('tooltip.js',bundle.outputFiles[0].text);
const popper=new URL('../node_modules/@popperjs/core/',import.meta.url),popperPackage=JSON.parse(await readFile(new URL('package.json',popper)));
files.set('THIRD-PARTY-LICENSES.txt',`Bootstrap ${version}\n\n`+await readFile(new URL('LICENSE',source),'utf8')+`\n\n${popperPackage.name} ${popperPackage.version}\n\n`+await readFile(new URL('LICENSE.md',popper),'utf8'));
for (const [name,content] of files) {
  const path = new URL(name,target);
  if (check) {
    if(await readFile(path,'utf8')!==content) throw new Error(`Regenerate bundled asset: ${name}`);
  } else await writeFile(path,content);
}
if(check && (await readdir(target)).some(name=>name.endsWith('.svg') && !images.has(name))) throw new Error('Unexpected bundled SVG');
console.log(`${check ? 'Verified' : 'Bundled'} Bootstrap ${version} CSS, Tooltip with Popper, and ${images.size} local SVGs.`);
