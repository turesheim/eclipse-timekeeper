// Regenerate the committed CSS-only distribution from the exact npm lockfile.
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
for (const [name,content] of files) {
  const path = new URL(name,target);
  if (check) {
    if(await readFile(path,'utf8')!==content) throw new Error(`Regenerate bundled asset: ${name}`);
  } else await writeFile(path,content);
}
if(check && (await readdir(target)).some(name=>name.endsWith('.svg') && !images.has(name))) throw new Error('Unexpected bundled SVG');
console.log(`${check ? 'Verified' : 'Bundled'} Bootstrap ${version} CSS and ${images.size} local SVGs; no JavaScript.`);
