// Regenerate the committed CSS-only distribution from the exact npm lockfile.
import {readFile, writeFile, mkdir, readdir, rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const source = new URL('../node_modules/bootstrap/', import.meta.url);
const target = new URL('../src/assets/bootstrap/', import.meta.url);
const {version} = JSON.parse(await readFile(new URL('package.json', source)));
if (version !== '5.3.8') throw new Error('Expected Bootstrap 5.3.8');
await mkdir(target, {recursive:true});
for (const name of await readdir(target)) if (name.endsWith('.svg')) await rm(new URL(name,target));
let css = await readFile(new URL('dist/css/bootstrap.min.css', source), 'utf8');
const images = new Map();
css = css.replace(/url\("data:image\/svg\+xml,([^"\n]+)"\)/g, (_, data) => {
  const svg = decodeURIComponent(data);
  const name = `image-${createHash('sha256').update(svg).digest('hex').slice(0,12)}.svg`;
  images.set(name,svg);
  return `url("./${name}")`;
}).replace(/\/\*# sourceMappingURL=.*?\*\//g, '');
if (/data:|https?:\/\//.test(css.replace(/\/\*[\s\S]*?\*\//g,''))) throw new Error('Unexpected external or embedded asset');
await writeFile(new URL('bootstrap.min.css',target),css);
await writeFile(new URL('LICENSE',target),await readFile(new URL('LICENSE',source)));
for (const [name,svg] of images) await writeFile(new URL(name,target),svg);
console.log(`Bundled Bootstrap ${version} CSS and ${images.size} local SVGs; no JavaScript.`);
