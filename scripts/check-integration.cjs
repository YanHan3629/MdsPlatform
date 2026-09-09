// Run from the repository root after starting the complete project.
// Credentials come from QA_TEST_USERNAME / QA_TEST_PASSWORD or ignored .env.qa-test.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const local = Object.fromEntries((fs.existsSync(path.join(root, '.env.qa-test'))
  ? fs.readFileSync(path.join(root, '.env.qa-test'), 'utf8') : '').split(/\r?\n/)
  .filter(line => line.includes('=')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const base = process.env.DATASPACE_URL || 'http://localhost:8888';
let token;
async function api(route, body, method = body ? 'POST' : 'GET') {
  const multipart = body instanceof FormData;
  const r = await fetch(base + route, { method, signal: AbortSignal.timeout(360000),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body && !multipart ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? (multipart ? body : JSON.stringify(body)) : undefined });
  if (!r.ok) throw new Error(`${route}: HTTP ${r.status}: ${await r.text()}`);
  return r;
}
async function main() {
  const denied = await fetch(base + '/api/data-space/qa/datasets');
  assert.equal(denied.status, 401);
  const login = await (await api('/api/auth/login', {
    userName: process.env.QA_TEST_USERNAME || local.QA_TEST_USERNAME,
    password: process.env.QA_TEST_PASSWORD || local.QA_TEST_PASSWORD
  })).json();
  token = login.token; assert.ok(token);
  const datasets = await (await api('/api/data-space/qa/datasets')).json();
  assert.ok(datasets.length, 'No accessible READY datasets');
  console.log(JSON.stringify({ check: 'login_and_datasets', datasets: datasets.map(d => d.datasetName) }));
  const scope = datasets[0];
  const ids = Object.fromEntries(['datasetId', 'versionId', 'indexVersionId'].map(k => [k, scope[k]]));
  const wrong = await fetch(base + '/api/data-space/qa', { method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...ids, indexVersionId: '00000000-0000-0000-0000-000000000000', question: 'test' }) });
  assert.equal(wrong.status, 400);
  const plain = await (await api('/api/data-space/qa', { question: '请用一句话概括资料。',
    inputMode: 'user_data_only', texts: ['冰箱温度由4摄氏度升至8摄氏度。'], maxTokens: 96 })).json();
  assert.equal(plain.backend, 'vllm'); assert.ok(plain.answer);
  console.log(JSON.stringify({ check: 'json_qa', answer: plain.answer, retrieved: plain.retrieved.length }));
  const search = await (await api('/api/data-space/qa/search', { ...ids, query: '冰箱', topK: 2 })).json();
  assert.ok(search.items.length, 'Real search returned no evidence');
  const hit = search.items.find(i => (i.contentType || '').startsWith('image/')) || search.items[0];
  const source = await api(`/api/data-space/qa/assets/${ids.datasetId}/${ids.versionId}/${hit.assetId}`);
  const bytes = await source.arrayBuffer(); assert.ok(bytes.byteLength);
  console.log(JSON.stringify({ check: 'search_and_source', count: search.items.length, sourceBytes: bytes.byteLength }));
  const rag = await (await api('/api/data-space/qa', { ...ids, question: '请描述检索到的冰箱内容，简短回答。',
    inputMode: 'question_only', retrievalMode: 'search_service', retrievalType: 'text_to_image', topK: 2, maxTokens: 96 })).json();
  assert.equal(rag.retrieval_mode, 'search_service'); assert.ok(rag.retrieved.length);
  assert.equal(rag.meta.intent_analysis_used, false);
  console.log(JSON.stringify({ check: 'real_rag', backend: rag.backend, retrieved: rag.retrieved.length,
    imageCount: rag.meta.image_count, answer: rag.answer.slice(0, 260) }));
  if ((hit.contentType || '').startsWith('image/')) {
    const form = new FormData();
    Object.entries({ ...ids, question: '请简短说明图片及检索资料中的内容。', inputMode: 'hybrid',
      retrievalMode: 'search_service', retrievalType: 'dual', topK: '2', maxTokens: '96' }).forEach(([k, v]) => form.set(k, v));
    form.append('images', new Blob([bytes], { type: hit.contentType }), 'query.png');
    const dual = await (await api('/api/data-space/qa', form)).json();
    assert.equal(dual.meta.retrieval_type, 'dual'); assert.equal(dual.meta.intent_analysis_used, false);
    assert.ok(dual.retrieved.length); assert.equal(dual.backend, 'vllm');
    console.log(JSON.stringify({ check: 'multipart_dual_rag', retrieved: dual.retrieved.length, imageCount: dual.meta.image_count }));
  }
  console.log('INTEGRATION_OK');
}
main().catch(e => { console.error(e); process.exitCode = 1; });
