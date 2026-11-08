import {test} from 'node:test';
import assert from 'node:assert/strict';
import {svgDocument} from '../editor/plantuml-svg-dom.js';
import {plantumlSourceAllowed} from '../src/plantuml-svg.js';

test('diagram previews admit ordinary URL labels while refusing resource directives and images',()=>{
  assert.equal(plantumlSourceAllowed('@startuml\nAlice -> Bob: https://example.invalid\n@enduml'),true);
  for(const source of ['!include local.puml','  !INCLUDEURL https://example.invalid','!import library','!theme external','Alice -> Bob: <img:file:///private/image>'])assert.equal(plantumlSourceAllowed(source),false);
});
test('worker SVG construction escapes authored labels, omits embedded source and bounds resource use',()=>{
  const canvas={getContext:()=>({measureText:()=>({actualBoundingBoxLeft:1,actualBoundingBoxRight:20,actualBoundingBoxAscent:10,actualBoundingBoxDescent:3})})},adapter=svgDocument(canvas),document=adapter.document;
  const root=document.createElementNS('http://www.w3.org/2000/svg','svg'),text=document.createElementNS('http://www.w3.org/2000/svg','text');text.textContent='<script>& private 🚀';root.appendChild(text);root.appendChild(document.createProcessingInstruction('plantuml-src','private original source'));
  const svg=adapter.serialize(root);assert.ok(svg.includes('&lt;script&gt;&amp; private 🚀'));assert.ok(!svg.includes('<script>'));assert.ok(!svg.includes('private original source'));assert.deepEqual(text.getBBox(),{x:-1,y:-10,width:21,height:13});
  assert.throws(()=>text.appendChild(root));assert.throws(()=>root.setAttribute('invalid"name','value'));assert.throws(()=>document.createElement('script'));assert.throws(()=>{text.textContent='x'.repeat(2_000_001);});
});
