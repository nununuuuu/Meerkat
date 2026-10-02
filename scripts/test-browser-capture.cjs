// Run with: node --test scripts/test-browser-capture.cjs
// Execute the exact raw JavaScript injected into WebView, including its regexes.
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(
  'app/src/main/java/com/resourcesniffer/app/MainActivity.kt', 'utf8');
const script = source.match(/private fun browserCaptureScript\(\): String = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const domScript = source.match(/private fun scanDomResources[\s\S]*?val script = """([\s\S]*?)"""\.trimIndent\(\)/)[1];

function browser({nodes = [], inline = [], fetchResponse, performanceEntries = [], pageUrl = "https://page.test/folder/"} = {}) {
  const reports = [];
  const posters = [];
  let mutation;
  class BrowserURL extends URL {
    static createObjectURL() { return 'blob:https://page.test/blob'; }
  }
  class Response {
    constructor(url, mime, text = '') {
      this.url = url;
      this.headers = {get: () => mime};
      this.bodyText = text;
    }
    clone() { return new Response(this.url, this.headers.get(), this.bodyText); }
    text() { return Promise.resolve(this.bodyText); }
    json() { return Promise.resolve(JSON.parse(this.bodyText)); }
    arrayBuffer() { return Promise.resolve(new ArrayBuffer(0)); }
  }
  class XMLHttpRequest {
    open() {}
    send() { this.listeners.load.call(this); }
    addEventListener(name, handler) { (this.listeners ||= {})[name] = handler; }
    getResponseHeader() { return this.mime || ''; }
  }
  const context = vm.createContext({
    URL: BrowserURL, Response, XMLHttpRequest, Blob, ArrayBuffer,
    document: {
      baseURI: 'https://page.test/folder/', readyState: 'complete',
      documentElement: {}, images: [],
      querySelectorAll(selector) { return selector === 'script:not([src])' ? inline : nodes; },
      addEventListener() {},
    },
    location: {href: pageUrl},
    navigator: {}, performance: {getEntriesByType: () => performanceEntries},
    MutationObserver: class {
      constructor(callback) { mutation = callback; }
      observe() {}
    },
    fetch: () => Promise.resolve(fetchResponse || new Response('https://cdn.test/asset', 'video/mp4')),
    MeerkatCapture: {resource(url, mime, referer) { reports.push({url, mime, referer}); },
      videoPoster(url, poster, referer) { posters.push({url, poster, referer}); }},
  });
  context.window = context;
  vm.runInContext(script, context, {timeout: 1000});
  return {context, reports, posters, Response, mutate: records => mutation(records)};
}

function image(src) {
  return {nodeType: 1, tagName: 'IMG', currentSrc: src,
    hasAttribute: key => key === 'src', getAttribute: () => src};
}

test('both injected scripts parse as JavaScript', () => {
  new vm.Script(script);
  new vm.Script(domScript);
});

test('captures MIME of extensionless fetch responses without changing the response', async () => {
  const {context, reports} = browser();
  const response = await context.fetch(new context.URL('https://cdn.test/asset'));
  assert.equal(response.url, 'https://cdn.test/asset');
  assert.ok(reports.some(r => r.url === response.url && r.mime === 'video/mp4'));
});

test('finds JSON escaped URLs and relative manifests in inline player configuration', () => {
  const {reports} = browser({inline: [{textContent:
    '{"url":"https:\\/\\/cdn.test\\/video.mp4?token=a&x=2","manifest":"../master.m3u8"}'}]});
  assert.ok(reports.some(r => r.url === 'https://cdn.test/video.mp4?token=a&x=2'));
  assert.ok(reports.some(r => r.url === 'https://page.test/master.m3u8'));
});

test('JSON.parse hook preserves values and discovers nested media configuration', () => {
  const {context, reports} = browser();
  assert.equal(vm.runInContext('JSON.parse(\'{"nested":{"url":"https://cdn.test/movie.mp4"}}\').nested.url', context),
    'https://cdn.test/movie.mp4');
  assert.ok(reports.some(r => r.url === 'https://cdn.test/movie.mp4'));
  assert.throws(() => vm.runInContext('JSON.parse("{")', context));
});

test('captures json XHR responses and response MIME', () => {
  const {context, reports} = browser();
  vm.runInContext('const xhr = new XMLHttpRequest(); xhr.open("GET", "/api"); xhr.responseType = "json"; xhr.responseURL = "https://page.test/api"; xhr.mime = "application/json"; xhr.response = {url:"https://cdn.test/audio.mp3"}; xhr.send();', context);
  assert.ok(reports.some(r => r.url === 'https://cdn.test/audio.mp3'));
});

test('walks children added by a dynamic player', () => {
  const {mutate, reports} = browser();
  mutate([{target: {}, addedNodes: [{nodeType: 1,
    querySelectorAll: () => [image('https://cdn.test/no-extension')]}]}]);
  assert.ok(reports.some(r => r.url === 'https://cdn.test/no-extension' && r.mime === 'image/*'));
});

test('deduplicates repeated discovery and still enriches MIME', () => {
  const node = image('https://cdn.test/image.jpg');
  const {reports, mutate} = browser({nodes: [node]});
  const count = reports.length;
  mutate([{target: node, addedNodes: []}]);
  assert.equal(reports.length, count);
  assert.ok(reports.some(r => r.mime === 'image/*'));
});

test('srcset whitespace resolves separate image URLs', () => {
  const {reports} = browser({nodes: [{
    nodeType: 1, hasAttribute: () => false,
    srcset: '/small.jpg 320w, /large.jpg 1280w',
  }]});
  assert.deepEqual(reports.map(r => r.url),
    ['https://page.test/small.jpg', 'https://page.test/large.jpg']);
});

test('authentication documents keep browser APIs untouched', () => {
  for (const path of ['/accounts/login/', '/signin', '/oauth2/authorize', '/challenge/123']) {
    const { context, reports } = browser({pageUrl: 'https://example.com' + path});
    assert.equal(context.__meerkatCaptureInstalled, undefined, path);
    assert.equal(vm.runInContext('JSON.parse.toString().includes("[native code]")', context), true, path);
    assert.equal(reports.length, 0, path);
  }
});


test('associates a video poster with its media URL for list thumbnails', () => {
  const {posters} = browser({nodes:[{nodeType:1, tagName:'VIDEO', currentSrc:'https://cdn.test/movie.mp4', poster:'/cover.jpg', hasAttribute:()=>false}]});
  assert.equal(posters.length, 1);
  assert.equal(posters[0].url, 'https://cdn.test/movie.mp4');
  assert.equal(posters[0].poster, 'https://page.test/cover.jpg');
});

test('repeated video mutations report a poster once but accept a changed source', () => {
  const video = {nodeType:1, tagName:'VIDEO', currentSrc:'https://cdn.test/one.mp4', poster:'/one.jpg', hasAttribute:()=>false};
  const {posters, mutate} = browser({nodes:[video]});
  for (let i=0; i<100; i++) mutate([{target:video, addedNodes:[]}]);
  assert.equal(posters.length, 1);
  video.currentSrc = 'https://cdn.test/two.mp4';
  video.poster = '/two.jpg';
  mutate([{target:video, addedNodes:[]}]);
  assert.equal(posters.length, 2);
  assert.equal(posters[1].url, video.currentSrc);
});


test('large opaque page data does not block initialization or invent resources', () => {
  const {reports} = browser({inline:[{textContent:'A'.repeat(200000)}]});
  assert.equal(reports.length, 0);
});

test('large JSON strings remain intact and scanning has a shared work budget', () => {
  const {context, reports} = browser();
  context.payload = JSON.stringify({data:'A'.repeat(200000), media:'https://cdn.test/movie.mp4'});
  const length = vm.runInContext('JSON.parse(payload).data.length', context, {timeout:1000});
  assert.equal(length, 200000);
  assert.ok(reports.some(r => r.url === 'https://cdn.test/movie.mp4'));
});
