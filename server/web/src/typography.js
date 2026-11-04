// This classic script runs after typography.css and before the page is painted.
// Only the font variables declared in that stylesheet can be saved or applied.
(() => {
  const storageKey = 'timekeeper.typography';
  const stylesheet = document.getElementById('typography-defaults');
  const properties = new Set([...stylesheet.sheet.cssRules[0].style].filter(name => name.startsWith('--font-')));
  let current = null, defaults;

  function invalid(code, values = {}) {
    const error = new Error(code);error.typographyCode = code;error.values = values;throw error;
  }
  function parse(text) {
    if (typeof text !== 'string' || text.length > 65536) invalid('syntax');
    const content = text.replace(/\/\*[\s\S]*?\*\//g, '');
    const block = /^\s*:root\s*\{([^{}]*)\}\s*$/.exec(content);
    if (!block) invalid('syntax');
    const values = new Map();
    for (const declaration of block[1].split(';')) {
      if (!declaration.trim()) continue;
      const match = /^\s*(--[a-z0-9-]+)\s*:\s*(.*?)\s*$/s.exec(declaration);
      if (!match) invalid('syntax');
      const [, name, value] = match;
      if (!properties.has(name)) invalid('property', {name});
      if (values.has(name)) invalid('duplicate', {name});
      // Font lengths and CSS math are accepted; references, URLs, priorities and
      // other declarations cannot turn a font preference into arbitrary CSS.
      const words = value.match(/[a-z]+/gi) || [];
      const units = /^(px|rem|em|pt|pc|cm|mm|in|vw|vh|vmin|vmax|cqi|cqw|cqh|clamp|min|max|calc)$/i;
      if (!/^[\d.\s%+*/(),a-z-]+$/i.test(value) || words.some(word => !units.test(word)) || !CSS.supports('font-size', value)) invalid('value', {name});
      if (name === '--font-base' && !/^(?:\d+(?:\.\d+)?|\.\d+)(?:px|%)$/.test(value)) invalid('base');
      // Reject literal zero sizes, which would hide the text being configured.
      if (/^(?:0+(?:\.0+)?|\.0+)(?:[a-z]+|%)?$/.test(value)) invalid('value', {name});
      values.set(name, value);
    }
    return values;
  }
  function apply(text) {
    const values = text === null ? new Map() : parse(text);
    for (const name of properties) document.documentElement.style.removeProperty(name);
    for (const [name, value] of values) document.documentElement.style.setProperty(name, value);
    current = text;
    window.dispatchEvent(new Event('timekeeper-typography-change'));
  }
  window.timekeeperTypography = {
    read: () => current,
    async defaults() {
      defaults ??= fetch(stylesheet.href, {credentials: 'same-origin', redirect: 'error'})
        .then(response => {if (!response.ok) throw new Error('Unable to load typography.css');return response.text();})
        .catch(error => {defaults = null;throw error;});
      return defaults;
    },
    save(text) {
      parse(text);
      // A failed storage write keeps both the draft and the previous sizes.
      localStorage.setItem(storageKey, text);apply(text);
    },
    reset() {localStorage.removeItem(storageKey);apply(null);}
  };
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved !== null) apply(saved);
  } catch { /* Unavailable storage or an invalid saved fragment uses defaults. */ }
  window.addEventListener('storage', event => {
    if (event.key !== storageKey && event.key !== null) return;
    try {apply(event.key === null ? null : event.newValue);}
    catch {apply(null);}
  });
})();
