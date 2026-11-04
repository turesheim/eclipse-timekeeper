// Only the font variables declared in typography.css can be applied. Saved CSS
// comes from the authenticated account profile, never from browser storage.
(() => {
  const stylesheet = document.getElementById('typography-defaults');
  const properties = new Set([...stylesheet.sheet.cssRules[0].style].filter(name => name.startsWith('--font-')));
  let current = null, defaults, owner = null, profile = null;

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
    if (text !== current) {
      for (const name of properties) document.documentElement.style.removeProperty(name);
      for (const [name, value] of values) document.documentElement.style.setProperty(name, value);
      current = text;
    }
    window.dispatchEvent(new Event('timekeeper-typography-change'));
  }
  window.timekeeperTypography = {
    read: () => current,
    profile: () => profile,
    validate: parse,
    connect(nextOwner) {
      if (owner === nextOwner) return;
      owner = nextOwner;profile = null;apply(null);
    },
    disconnect() {owner = null;profile = null;apply(null);},
    receive(reply) {
      const next = reply?.data;
      // Slow replies from a previous account or profile version have no effect.
      if (!owner || next?.ownerId !== owner || profile && next.version < profile.data.version) return;
      profile = reply;
      try {apply(next.typographyCss ?? null);}
      catch {apply(null);}
    },
    async defaults() {
      defaults ??= fetch(stylesheet.href, {credentials: 'same-origin', redirect: 'error'})
        .then(response => {if (!response.ok) throw new Error('Unable to load typography.css');return response.text();})
        .catch(error => {defaults = null;throw error;});
      return defaults;
    }
  };
})();
