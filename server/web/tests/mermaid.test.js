import {test} from 'node:test';
import assert from 'node:assert/strict';
import {renderDiagram} from '../editor/mermaid.js';

const diagrams={
  flowchart:'flowchart LR\n  Start --> Finish',
  sequence:'sequenceDiagram\n  Alice->>Bob: Hei, verden!',
  class:'classDiagram\n  Animal <|-- Duck',
  state:'stateDiagram-v2\n  [*] --> Working\n  Working --> [*]',
  er:'erDiagram\n  CUSTOMER ||--o{ ORDER : places',
  gantt:'gantt\n  dateFormat YYYY-MM-DD\n  section Work\n  Plan :2026-11-15, 2d',
  pie:'pie title Time\n  "Work" : 8\n  "Rest" : 16',
  mindmap:'mindmap\n  root((Dagsverk))\n    Notes\n    Calendar'
};

test('Pele renders common Mermaid diagram families without a page DOM',()=>{
  for(const [name,source]of Object.entries(diagrams)){
    const svg=renderDiagram(source);
    assert.match(svg,/^<svg\b/,name);assert.match(svg,/class="pele\b/,name);
    assert.match(svg,/viewBox="[^"]+"/,name);
    assert.doesNotMatch(svg,/<(?:script|foreignObject|image|a)\b|\son\w+=/i,name);
  }
});

test('diagram labels stay escaped and link/image directives cannot enable external content',()=>{
  const svg=renderDiagram('flowchart LR\n A["<script>alert(1)</script> & Øving"] --> B\n click B "https://example.invalid/private"');
  assert.match(svg,/&amp; Øving/);
  assert.doesNotMatch(svg,/<(?:script|a|image|foreignObject)\b|href=/i);
  const image=renderDiagram('---\nconfig:\n  images: true\n  links: true\n---\nflowchart LR\n A@{ img: "https://example.invalid/private.png", label: "Private" }');
  assert.doesNotMatch(image,/<(?:image|a)\b|href=/i);
});

test('invalid and oversized diagrams fail, including attempts to raise limits in source',()=>{
  assert.throws(()=>renderDiagram('not a diagram'));
  assert.throws(()=>renderDiagram('flowchart LR\n A --> B\n%%'+ 'x'.repeat(10000)));
  const edges=Array.from({length:301},(_,i)=>'A'+i+' --> A'+(i+1)).join('\n');
  assert.throws(()=>renderDiagram('---\nconfig:\n  maxEdges: 9999\n---\nflowchart LR\n'+edges));
  assert.match(renderDiagram(diagrams.flowchart),/^<svg\b/,'a rejected diagram must not poison the next render');
});

test('narrow layouts adapt and diagrams receive distinct SVG identifiers',()=>{
  const source='flowchart LR\n accTitle: Dagsverk\n A[Plan] --> B[Implement] --> C[Verify] --> D[Finish]';
  const wide=renderDiagram(source,{maxWidth:1200}),narrow=renderDiagram(source,{maxWidth:320});
  const bounds=svg=>svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/).slice(1).map(Number);
  const [wideWidth,wideHeight]=bounds(wide),[narrowWidth,narrowHeight]=bounds(narrow);
  assert.ok(narrowWidth<wideWidth);assert.ok(narrowHeight>wideHeight);
  const ids=svg=>[...svg.matchAll(/\sid="([^"]+)"/g)].map(match=>match[1]);
  assert.ok(ids(wide).length);assert.equal(ids(wide).some(id=>ids(narrow).includes(id)),false);
});
