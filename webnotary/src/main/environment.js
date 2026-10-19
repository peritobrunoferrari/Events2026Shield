'use strict';
/** Coleta dados do ambiente de captura para constar no manifesto e na ata. */
const os = require('node:os');

async function collectEnvironment({ appVersion, publicIpUrl } = {}) {
  const nets = os.networkInterfaces();
  const local_ips = [];
  for (const [name, list] of Object.entries(nets)) for (const n of list || []) if (!n.internal) local_ips.push(`${name}: ${n.address}`);
  const env = {
    app_version: appVersion || null,
    electron: process.versions.electron || null,
    chromium: process.versions.chrome || null,
    node: process.versions.node,
    os: `${os.type()} ${os.platform()}`,
    os_release: os.release(),
    arch: os.arch(),
    hostname: os.hostname(),
    user: os.userInfo().username,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    tz_offset_min: -new Date().getTimezoneOffset(),
    local_ips,
    public_ip: null,
    coletado_em: new Date().toISOString(),
  };
  if (publicIpUrl) {
    try {
      const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), 6000);
      const r = await fetch(publicIpUrl, { signal: ctrl.signal }); clearTimeout(t);
      env.public_ip = (await r.text()).trim().slice(0, 64);
    } catch { env.public_ip = null; }
  }
  return env;
}

module.exports = { collectEnvironment };
