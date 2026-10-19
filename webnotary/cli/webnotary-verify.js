#!/usr/bin/env node
'use strict';
/** CLI: node cli/webnotary-verify.js <pasta-da-sessao> [--servidor URL] [--json] */
const { verifySessionDir, renderVerification } = require('../src/core/verify');

(async () => {
  const args = process.argv.slice(2);
  const dir = args.find((a) => !a.startsWith('--'));
  if (!dir) { console.error('uso: webnotary-verify <pasta-da-sessao> [--servidor URL] [--json]'); process.exit(2); }
  const si = args.indexOf('--servidor');
  const registryUrl = si >= 0 ? args[si + 1] : undefined;
  const R = await verifySessionDir(dir, { registryUrl });
  if (args.includes('--json')) console.log(JSON.stringify(R, null, 2));
  else process.stdout.write(renderVerification(R));
  process.exit(R.ok ? 0 : 1);
})().catch((e) => { console.error('erro:', e.message); process.exit(2); });
