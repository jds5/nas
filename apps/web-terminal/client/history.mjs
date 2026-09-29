// Render only SGR presentation. Terminal controls, OSC links and HTML stay inert.
const palette = ['#263442', '#ef7777', '#9bc989', '#e8c77a', '#83b4ed', '#c59ae5', '#80d9c4', '#d8e2ee',
  '#8093a5', '#ff9999', '#b6e6a3', '#ffe0a0', '#a4ceff', '#dfbaff', '#a4f2df', '#ffffff'];
function color(n) {
  if (!Number.isInteger(n) || n < 0 || n > 255) return undefined;
  if (n < 16) return palette[n];
  if (n >= 232) { const c = 8 + (n - 232) * 10; return `rgb(${c}, ${c}, ${c})`; }
  const levels = [0, 95, 135, 175, 215, 255]; n -= 16;
  return `rgb(${levels[Math.floor(n / 36)]}, ${levels[Math.floor(n / 6) % 6]}, ${levels[n % 6]})`;
}
export function historyRuns(input) {
  // Strip string controls first, including unterminated strings through end of input.
  const text = String(input).replace(/\x1b(?:\][\s\S]*?(?:\x07|\x1b\\|$)|[PX^_][\s\S]*?(?:\x1b\\|$))/g, '');
  const tokens = /\x1b\[([0-9;:]*)([ -/]*)([@-~])|\x1b[^\[]|[^\x1b]+/g;
  const runs = []; let style = {}, match;
  while ((match = tokens.exec(text))) {
    if (match[0][0] === '\x1b') {
      if (match[3] !== 'm' || match[2] || match[1].includes(':')) continue;
      const codes = match[1].split(';').map(Number);
      for (let i = 0; i < codes.length; i++) {
        const c = codes[i];
        if (c === 0) style = {};
        else if (c === 1) style.fontWeight = '700';
        else if (c === 2) style.opacity = '0.7';
        else if (c === 3) style.fontStyle = 'italic';
        else if (c === 4) style.textDecoration = 'underline';
        else if (c === 9) style.textDecoration = 'line-through';
        else if (c === 22) { delete style.fontWeight; delete style.opacity; }
        else if (c === 23) delete style.fontStyle;
        else if (c === 24 || c === 29) delete style.textDecoration;
        else if (c === 39) delete style.color;
        else if (c === 49) delete style.backgroundColor;
        else if (c >= 30 && c <= 37) style.color = color(c - 30);
        else if (c >= 90 && c <= 97) style.color = color(c - 90 + 8);
        else if (c >= 40 && c <= 47) style.backgroundColor = color(c - 40);
        else if (c >= 100 && c <= 107) style.backgroundColor = color(c - 100 + 8);
        else if (c === 38 || c === 48) {
          const key = c === 38 ? 'color' : 'backgroundColor';
          const mode = codes[++i]; let value;
          if (mode === 5) value = color(codes[++i]);
          else if (mode === 2) {
            const rgb = codes.slice(i + 1, i + 4); i += 3;
            if (rgb.length === 3 && rgb.every(v => Number.isInteger(v) && v >= 0 && v <= 255)) value = `rgb(${rgb.join(', ')})`;
          }
          if (value) style[key] = value;
        }
      }
    } else {
      const value = match[0].replace(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/g, '');
      if (!value) continue;
      // Bound DOM allocation even for adversarial output; remaining text stays readable.
      if (runs.length >= 4096) runs[runs.length - 1].text += value;
      else runs.push({ text: value, style: { ...style } });
    }
  }
  return runs;
}
export function renderHistory(element, text) {
  const fragment = element.ownerDocument.createDocumentFragment();
  for (const run of historyRuns(text)) {
    const span = element.ownerDocument.createElement('span');
    span.textContent = run.text; Object.assign(span.style, run.style); fragment.append(span);
  }
  element.replaceChildren(fragment);
}
