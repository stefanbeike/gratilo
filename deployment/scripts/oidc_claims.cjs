'use strict';

// Diagnostic decoding only. AWS still verifies the JWT signature and trust policy.
const PUBLIC_CLAIMS = ['sub', 'aud', 'repository', 'ref', 'environment'];
const AUDIENCE = 'sts.amazonaws.com';
const SUBJECT_PREFIX = 'repo:stefanbeike@13099702/gratilo@1351208624:environment:';

function publicClaims(token) {
  try {
    if (typeof token !== 'string' || token.split('.').length !== 3) throw new Error();
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    const claims = Object.fromEntries(PUBLIC_CLAIMS.map(key => [key, payload[key] ?? null]));
    for (const [key, value] of Object.entries(claims)) {
      if (value === null || typeof value === 'string') continue;
      if (key === 'aud' && Array.isArray(value) && value.every(item => typeof item === 'string')) continue;
      throw new Error();
    }
    return claims;
  } catch {
    throw new Error('OIDC claims could not be decoded safely.');
  }
}

async function requestClaims(env, fetcher = fetch) {
  if (!env.ACTIONS_ID_TOKEN_REQUEST_URL || !env.ACTIONS_ID_TOKEN_REQUEST_TOKEN)
    throw new Error('GitHub OIDC request context is missing; check id-token: write.');
  try {
    const url = new URL(env.ACTIONS_ID_TOKEN_REQUEST_URL);
    if (url.protocol !== 'https:' || !url.hostname.endsWith('.actions.githubusercontent.com')) throw new Error();
    url.searchParams.set('audience', AUDIENCE);
    const response = await fetcher(url, {
      headers: { Authorization: `Bearer ${env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}` },
      redirect: 'error',
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) throw new Error();
    // Token stays in memory: never print, export, or write it to disk.
    return publicClaims((await response.json()).value);
  } catch {
    // Do not propagate HTTP bodies, URLs, request headers, or parser exceptions.
    throw new Error('GitHub OIDC claims request failed; response details suppressed.');
  }
}

function validateClaims(claims, expectedRef = 'refs/heads/main', expectedEnvironment = 'production') {
  if (!['production', 'release'].includes(expectedEnvironment)) throw new Error('Unexpected AWS environment.');
  if (expectedRef !== 'refs/heads/main' && !/^refs\/tags\/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(expectedRef))
    throw new Error('Expected main or a stable release tag.');
  const expected = { sub: SUBJECT_PREFIX + expectedEnvironment, aud: AUDIENCE,
    repository: 'stefanbeike/gratilo', ref: expectedRef, environment: expectedEnvironment };
  if (PUBLIC_CLAIMS.some(key => claims[key] !== expected[key]))
    throw new Error('OIDC claims differ from the expected production identity; compare the five printed claims with the IAM trust policy.');
}

async function diagnose(env, fetcher, write = text => process.stdout.write(text)) {
  const claims = await requestClaims(env, fetcher);
  write(JSON.stringify(claims, null, 2) + '\n');
  validateClaims(claims, env.EXPECTED_OIDC_REF, env.EXPECTED_OIDC_ENVIRONMENT);
}

if (require.main === module) {
  diagnose(process.env).catch(error => {
    process.stderr.write(error.message + '\n');
    process.exitCode = 1;
  });
}
module.exports = { publicClaims, requestClaims, validateClaims, diagnose };
