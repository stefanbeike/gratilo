'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { diagnose, publicClaims } = require('../scripts/oidc_claims.cjs');
const expected = {
  sub: 'repo:stefanbeike@13099702/gratilo@1351208624:environment:production',
  aud: 'sts.amazonaws.com', repository: 'stefanbeike/gratilo',
  ref: 'refs/heads/main', environment: 'production',
};
const env = { ACTIONS_ID_TOKEN_REQUEST_URL: 'https://vstoken.actions.githubusercontent.com/test?api-version=2',
  ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'unit-test-request-token' };
function token(payload) { return 'test-header.' + Buffer.from(JSON.stringify(payload)).toString('base64url') + '.test-signature'; }
function response(value) { return { ok: true, json: async () => ({ value }) }; }

test('prints exactly five public claims, never token, credentials, or other payload fields', async () => {
  const jwt = token({ ...expected, secret_marker: 'never-print-this', job_workflow_ref: 'not-allowlisted' });
  let output = '';
  await diagnose(env, async (url, options) => {
    assert.equal(url.searchParams.get('audience'), 'sts.amazonaws.com');
    assert.equal(options.headers.Authorization, 'Bearer unit-test-request-token');
    assert.equal(options.redirect, 'error');
    return response(jwt);
  }, text => { output += text; });
  assert.deepEqual(JSON.parse(output), expected);
  for (const value of [jwt, env.ACTIONS_ID_TOKEN_REQUEST_TOKEN, 'never-print-this', 'not-allowlisted'])
    assert.equal(output.includes(value), false);
});

test('mismatched subject, audience, repository, ref or environment fails closed', async () => {
  for (const key of Object.keys(expected)) {
    await assert.rejects(diagnose(env, async () => response(token({ ...expected, [key]: 'wrong' })), () => {}),
      /expected production identity/);
  }
});

test('errors suppress HTTP response, URL and exception details', async () => {
  await assert.rejects(diagnose(env, async () => { throw new Error('secret-response-marker'); }),
    error => !error.message.includes('secret-response-marker'));
  await assert.rejects(diagnose(env, async () => ({ ok: false, json: async () => { throw new Error('must-not-read'); } })),
    /details suppressed/);
  assert.throws(() => publicClaims('bad-secret-token'), error => !error.message.includes('bad-secret-token'));
  await assert.rejects(diagnose({}, async () => { throw new Error('must-not-call'); }), /id-token: write/);
});

test('rejects redirects or a non-GitHub token endpoint without sending credentials', async () => {
  let called = false;
  await assert.rejects(diagnose({ ...env, ACTIONS_ID_TOKEN_REQUEST_URL: 'https://example.invalid/' },
    async () => { called = true; }));
  assert.equal(called, false);
});

test('never prints nested objects smuggled into an allowlisted claim', async () => {
  let output = '';
  await assert.rejects(diagnose(env, async () => response(token({ ...expected, sub: { secret: 'hidden-value' } })),
    text => { output += text; }));
  assert.equal(output, '');
});
