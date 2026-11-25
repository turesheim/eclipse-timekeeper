import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';
import {drawioPngInfo,samePngBytes,withDrawioDescription} from '../src/drawio-png.js';
import {markdownImages} from '../editor/markdown-preview.js';

const original=new Uint8Array(await readFile(new URL('./fixtures/drawio/two-pages.drawio.png',import.meta.url)));
const chunk=(name,data)=>{const bytes=Buffer.alloc(data.length+12);bytes.writeUInt32BE(data.length);bytes.write(name,4);bytes.set(data,8);let crc=0xffffffff;for(const value of bytes.subarray(4,-4)){crc^=value;for(let i=0;i<8;i++)crc=crc&1?0xedb88320^(crc>>>1):crc>>>1;}bytes.writeUInt32BE((crc^0xffffffff)>>>0,bytes.length-4);return bytes;};
const withText=(type,text)=>{let at=8,parts=[original.subarray(0,8)];while(at<original.length){const n=new DataView(original.buffer,original.byteOffset+at,4).getUint32(0),part=original.subarray(at,at+n+12),name=String.fromCharCode(...part.subarray(4,8));if(!['tEXt','zTXt','iTXt'].includes(name)){if(name==='IEND')parts.push(chunk(type,text));parts.push(part);}at+=n+12;}return Buffer.concat(parts);};
test('a real multi-page draw.io PNG is recognized without modifying any bytes',()=>{
  const before=original.slice(),info=drawioPngInfo(original);assert.equal(info.editable,true);assert.ok(info.width>100);assert.ok(info.height>50);assert.deepEqual(original,before);assert.ok(samePngBytes(original,before));assert.ok(!samePngBytes(original,original.subarray(1)));
});
test('ordinary PNGs, corrupt/truncated data and unrelated text never claim an editable diagram',()=>{
  assert.equal(drawioPngInfo(withText('tEXt',Buffer.from('Author\0<mxfile/>'))).editable,false);
  for(const data of [original.subarray(0,-1),new Uint8Array(5_000_001),new TextEncoder().encode('<svg/>')])assert.equal(drawioPngInfo(data),null);
  const damaged=original.slice();damaged[45]^=1;assert.equal(drawioPngInfo(damaged),null);
  assert.equal(drawioPngInfo(withText('tEXt',Buffer.from('mxfile\0not diagram data'))).editable,false);
});
test('legacy compressed and international text metadata remain opaque and intact',()=>{
  const xml=Buffer.from('<mxfile><diagram name="One"/></mxfile>');
  for(const [type,data] of [['zTXt',Buffer.concat([Buffer.from('mxfile\0\0'),deflateSync(xml)])],['iTXt',Buffer.concat([Buffer.from('mxfile\0\0\0\0\0'),xml])],['iTXt',Buffer.concat([Buffer.from('mxfile\0\x01\0\0\0'),deflateSync(xml)])]]){
    const bytes=withText(type,data),before=bytes.slice();assert.equal(drawioPngInfo(bytes).editable,true);assert.deepEqual(bytes,before);
  }
});
test('shared UTF-8 descriptions can be updated or cleared without changing editable PNG data',()=>{
  const caption='  Økonomi 🤝 北京  ';
  const withCaption=withDrawioDescription(original,caption);
  assert.deepEqual(drawioPngInfo(withCaption),{...drawioPngInfo(original),description:caption.trim()});
  const revised=withDrawioDescription(withCaption,'Updated caption');
  assert.equal(drawioPngInfo(revised).description,'Updated caption');
  assert.deepEqual(withDrawioDescription(revised,''),original);
});
test('diagram management finds prose image references and excludes escaped or literal examples',()=>{
  const source='![Overview](diagrams/system.drawio.png)\n\n![Reference][png]\n\n[png]: ../Images/second.png\n\n![[Images/wiki.png|200]]\n\n\\![[escaped.png]]\n\n`![inline](code.png)`\n\n```md\n![[fenced.png]]\n```\n\n<div>![html](html.png)</div>\n';
  assert.deepEqual(markdownImages(source),[{reference:'diagrams/system.drawio.png',kind:'relative',alt:'Overview'},{reference:'../Images/second.png',kind:'relative',alt:'Reference'},{reference:'Images/wiki.png|200',kind:'wiki',alt:'wiki.png'}]);
});
