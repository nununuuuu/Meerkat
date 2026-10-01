const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/resourcesniffer/app/settings/CredentialFill.kt', 'utf8');
const template = source.match(/return """([\s\S]*?)"""\.trimIndent\(\)/)[1];
function run({ origin = 'https://example.com', frame = false, passwordCount = 1, newPassword = false, hidden = false } = {}) {
  class Input { constructor() { this.disabled = false; this.readOnly = false; this.events = []; }
    getClientRects() { return hidden ? [] : [1]; }
    dispatchEvent(e) { this.events.push(e.type); }
    set value(v) { this.saved = v; }
  }
  const user = new Input();
  const passwords = Array.from({length:passwordCount}, () => Object.assign(new Input(), {autocomplete:newPassword ? 'new-password' : 'current-password'}));
  const document = { querySelectorAll: selector => selector === 'input[type="password"]' ? passwords : [user] };
  passwords.forEach(p => p.form = document);
  const window = {}; window.top = frame ? {} : window;
  const credentials = {origin:'https://example.com', username:'someone@example.com', password:'quotes"\\\n; location="https://evil.test";'};
  const result = vm.runInNewContext(template.replace('$data', JSON.stringify(credentials)), {window, location:{origin}, document, HTMLInputElement:Input, Event:class { constructor(type) { this.type=type; } }});
  return {result, user, passwords, credentials};
}
test('fills exact-origin login fields without submitting and preserves special characters', () => {
  const r=run(); assert.equal(r.result,'filled'); assert.equal(r.passwords[0].saved,r.credentials.password); assert.deepEqual(r.user.events,['input','change']);
});
test('does not fill lookalike domains, alternate ports or embedded frames', () => {
  for(const options of [{origin:'https://example.com.evil.test'}, {origin:'https://example.com:8443'}, {frame:true}]) {
    const r=run(options); assert.equal(r.result,'origin-mismatch'); assert.equal(r.passwords[0].saved,undefined);
  }
});
test('does not fill hidden fields or account creation/change-password forms', () => {
  for(const options of [{passwordCount:2},{newPassword:true},{hidden:true}]) {
    const r=run(options); assert.notEqual(r.result,'filled'); assert.equal(r.passwords[0].saved,undefined);
  }
});
