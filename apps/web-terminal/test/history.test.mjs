import { test } from 'node:test';
import assert from 'node:assert/strict';
import { historyRuns } from '../client/history.mjs';
test('history preserves columns, Unicode, colors and emphasis with resets', () => {
  const runs = historyRuns('\x1b[1;31m标题\x1b[0m\n│ 一    二 │\n\x1b[38;2;12;34;56mRGB\x1b[39;4m链接\x1b[24m正常');
  assert.equal(runs.map(r => r.text).join(''), '标题\n│ 一    二 │\nRGB链接正常');
  assert.equal(runs[0].style.fontWeight, '700'); assert.ok(runs[0].style.color);
  assert.deepEqual(runs[1].style, {}); assert.equal(runs[2].style.color, 'rgb(12, 34, 56)');
  assert.deepEqual(runs[3].style, { textDecoration: 'underline' }); assert.deepEqual(runs[4].style, {});
  assert.equal(historyRuns('\x1b[38;5;196mred')[0].style.color, 'rgb(255, 0, 0)');
});
test('history discards terminal controls and OSC payloads without interpreting HTML', () => {
  const runs = historyRuns('\x1b]8;;javascript:alert(1)\x1b\\<img src=x onerror=alert(1)>\x1b]8;;\x07\x1b[2J\x1b[Hsafe\x1bPsecret\x1b\\\x1b]52;c;secret');
  assert.equal(runs.map(r => r.text).join(''), '<img src=x onerror=alert(1)>safe');
});
test('history bounds spans while preserving all text', () => {
  const runs = historyRuns('\x1b[31mx\x1b[0my'.repeat(10000));
  assert.equal(runs.length, 4096); assert.equal(runs.map(r => r.text).join(''), 'xy'.repeat(10000));
});
