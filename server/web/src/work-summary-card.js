/** Shared two-line work totals for the monthly calendar and Today sidebar. */
export function workSummaryCard({tag='div',className='',title,color,swatch=false}) {
  const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
  const card=node(tag,undefined,`calendar-summary-card calendar-work-summary ${className}`.trim());
  if(tag==='button')card.type='button';
  if(/^#[0-9a-f]{6}$/i.test(color))card.style.setProperty('--client-color',color);
  const heading=node('strong',title,'calendar-summary-title');heading.title=title;
  if(swatch){card.classList.add('calendar-summary-swatch');heading.classList.add('client-color');heading.replaceChildren(node('span',title,'calendar-summary-title-text'));}
  const line=node('span',undefined,'calendar-summary-total'),duration=node('span',undefined,'calendar-summary-time');
  line.append(duration);card.append(heading,' ',line);
  return {card,heading,line,duration};
}
