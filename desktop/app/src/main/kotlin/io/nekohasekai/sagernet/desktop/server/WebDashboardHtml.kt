package io.nekohasekai.sagernet.desktop.server

/**
 * Self-contained, zero-CDN-dependency single-page Web Management UI served at `/ui/`.
 *
 * Uses strict DOM node construction (`textContent`) rather than string interpolation
 * so untrusted subscription/profile/rule names cannot trigger XSS.
 */
object WebDashboardHtml {

    val INDEX_HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Owenclave Web Console</title>
<style>
  :root {
    --bg: #0b0f17;
    --panel: #121826;
    --panel-alt: #182033;
    --border: #253049;
    --text: #e6edf7;
    --muted: #8b9bb4;
    --accent: #4f8cff;
    --accent-hover: #3a78f0;
    --ok: #22c55e;
    --warn: #f59e0b;
    --err: #ef4444;
    --chip: #1d273d;
    --chip-active: #2563eb;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Inter, Roboto, sans-serif;
    font-size: 14px;
    line-height: 1.45;
  }
  .layout {
    display: flex;
    min-height: 100vh;
  }
  .sidebar {
    width: 230px;
    background: var(--panel);
    border-right: 1px solid var(--border);
    padding: 20px 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .brand {
    padding: 4px 10px 14px;
    border-bottom: 1px solid var(--border);
    margin-bottom: 8px;
  }
  .brand h1 {
    margin: 0;
    font-size: 18px;
    font-weight: 700;
    letter-spacing: 0.2px;
  }
  .brand .sub {
    color: var(--muted);
    font-size: 12px;
    margin-top: 2px;
  }
  .nav-btn {
    background: transparent;
    color: var(--muted);
    border: 1px solid transparent;
    text-align: left;
    padding: 10px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 13.5px;
    font-weight: 500;
    transition: all 0.12s ease;
  }
  .nav-btn:hover { color: var(--text); background: var(--panel-alt); }
  .nav-btn.active {
    color: #fff;
    background: var(--chip-active);
  }
  .main {
    flex: 1;
    padding: 22px 28px;
    max-width: 1320px;
    overflow-x: hidden;
  }
  .topbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 14px 18px;
    margin-bottom: 18px;
  }
  .status-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  .dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--err);
    display: inline-block;
  }
  .dot.ok { background: var(--ok); box-shadow: 0 0 8px rgba(34,197,94,0.6); }
  .badge {
    background: var(--chip);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 3px 9px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 500;
  }
  .badge.https { border-color: #22c55e88; color: #86efac; }
  .badge.http { border-color: #60a5fa88; color: #93c5fd; }
  .mode-switch {
    display: inline-flex;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    overflow: hidden;
  }
  .mode-switch button {
    background: transparent;
    color: var(--muted);
    border: none;
    padding: 6px 12px;
    cursor: pointer;
    font-size: 12.5px;
    font-weight: 600;
  }
  .mode-switch button.active {
    background: var(--accent);
    color: #fff;
  }
  .grid-2 {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
    gap: 16px;
    margin-bottom: 18px;
  }
  .card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px 18px;
    margin-bottom: 16px;
  }
  .card h2 {
    margin: 0 0 12px;
    font-size: 15px;
    font-weight: 600;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .chip {
    background: var(--chip);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 6px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 12.5px;
    transition: all 0.12s ease;
  }
  .chip:hover { border-color: var(--accent); }
  .chip.active {
    background: var(--chip-active);
    border-color: var(--accent);
    color: #fff;
    font-weight: 600;
  }
  .row {
    display: flex;
    gap: 10px;
    margin-bottom: 10px;
    flex-wrap: wrap;
    align-items: center;
  }
  input[type="text"], input[type="password"], input[type="number"], select, textarea {
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 10px;
    font-size: 13px;
    outline: none;
  }
  input:focus, select:focus, textarea:focus { border-color: var(--accent); }
  .grow { flex: 1; min-width: 140px; }
  textarea { width: 100%; min-height: 92px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .btn {
    background: var(--accent);
    color: #fff;
    border: none;
    border-radius: 8px;
    padding: 8px 14px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
  }
  .btn:hover { background: var(--accent-hover); }
  .btn.secondary {
    background: var(--panel-alt);
    border: 1px solid var(--border);
    color: var(--text);
  }
  .btn.danger {
    background: rgba(239,68,68,0.16);
    border: 1px solid rgba(239,68,68,0.45);
    color: #fca5a5;
  }
  .btn.sm { padding: 5px 10px; font-size: 12px; }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  th, td {
    text-align: left;
    padding: 9px 10px;
    border-bottom: 1px solid var(--border);
  }
  th { color: var(--muted); font-weight: 500; font-size: 12px; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  .logs-box {
    background: #070a10;
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 12px;
    max-height: 540px;
    overflow-y: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  .log-line { padding: 2px 0; border-bottom: 1px solid rgba(255,255,255,0.03); word-break: break-all; }
  .log-error { color: #f87171; }
  .log-warning { color: #fbbf24; }
  .log-info { color: #cbd5e1; }
  .toast {
    position: fixed;
    bottom: 20px;
    right: 20px;
    background: var(--panel-alt);
    border: 1px solid var(--accent);
    color: var(--text);
    padding: 10px 16px;
    border-radius: 10px;
    box-shadow: 0 8px 24px rgba(0,0,0,0.45);
    display: none;
    z-index: 100;
  }
  .auth-modal {
    position: fixed;
    inset: 0;
    background: rgba(5, 8, 15, 0.82);
    display: none;
    align-items: center;
    justify-content: center;
    z-index: 90;
  }
  .auth-box {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 24px;
    width: 360px;
  }
  @media (max-width: 780px) {
    .layout { flex-direction: column; }
    .sidebar { width: 100%; flex-direction: row; overflow-x: auto; align-items: center; padding: 10px; }
    .brand { border-bottom: none; margin-bottom: 0; padding: 0 10px 0 4px; }
    .main { padding: 14px; }
  }
</style>
</head>
<body>
<div class="layout">
  <aside class="sidebar">
    <div class="brand">
      <h1>Owenclave</h1>
      <div class="sub" id="versionLabel">Mihomo Web Console</div>
    </div>
    <button class="nav-btn active" data-tab="proxies">Proxies &amp; Groups</button>
    <button class="nav-btn" data-tab="subscriptions">Subscriptions</button>
    <button class="nav-btn" data-tab="rules">Routing Rules</button>
    <button class="nav-btn" data-tab="settings">Settings</button>
    <button class="nav-btn" data-tab="logs">Live Logs</button>
  </aside>

  <main class="main">
    <div class="topbar">
      <div class="status-row">
        <span class="dot" id="coreDot"></span>
        <strong id="activeProfileLabel">Loading...</strong>
        <span class="badge" id="activeProtocolBadge">-</span>
        <span class="badge mono" id="inboundBadge">mixed: -</span>
        <span class="badge mono" id="latencyBadge">ping: -</span>
      </div>
      <div class="status-row">
        <div class="mode-switch" id="modeSwitch">
          <button data-mode="rule">Rule</button>
          <button data-mode="global">Global</button>
          <button data-mode="direct">Direct</button>
        </div>
        <button class="btn secondary sm" id="pingActiveBtn">Ping</button>
        <button class="btn secondary sm" id="reloadBtn">Reload</button>
        <button class="btn secondary sm" id="tokenBtn">API Secret</button>
      </div>
    </div>

    <section id="tab-proxies">
      <div class="grid-2">
        <div class="card">
          <h2>Add HTTP / HTTPS Proxy</h2>
          <div class="row">
            <select id="httpProtoSelect">
              <option value="http">HTTP</option>
              <option value="https">HTTPS (TLS)</option>
            </select>
            <input type="text" id="httpName" class="grow" placeholder="Profile name (optional)">
          </div>
          <div class="row">
            <input type="text" id="httpHost" class="grow" placeholder="Server host or IP (e.g. proxy.example.com)">
            <input type="number" id="httpPort" style="width:105px" value="8080" min="1" max="65535" placeholder="Port">
          </div>
          <div class="row">
            <input type="text" id="httpUser" class="grow" placeholder="Username (optional)">
            <input type="password" id="httpPass" class="grow" placeholder="Password (optional)">
          </div>
          <div class="row" id="httpsTlsRow" style="display:none">
            <input type="text" id="httpSni" class="grow" placeholder="TLS SNI (optional)">
            <label style="font-size:12.5px;color:var(--muted);display:flex;align-items:center;gap:5px;">
              <input type="checkbox" id="httpInsecure"> Allow insecure
            </label>
          </div>
          <div class="row">
            <label style="font-size:12.5px;color:var(--muted);display:flex;align-items:center;gap:5px;">
              <input type="checkbox" id="httpUot"> UDP over TCP (CONNECT-UDP)
            </label>
            <div style="flex:1"></div>
            <button class="btn" id="addHttpProxyBtn">Add HTTP Proxy</button>
          </div>
        </div>

        <div class="card">
          <h2>Import Share Links / Clash YAML / JSON</h2>
          <textarea id="importText" placeholder="http://user:pass@host:8080#Name&#10;https://user:pass@host:443?sni=host#TLS&#10;vless://... / hy2://... / naive+https://... / proxies: ..."></textarea>
          <div class="row" style="margin-top:8px;margin-bottom:0">
            <button class="btn" id="importLinksBtn">Import Profiles</button>
            <button class="btn secondary" id="importRawBtn">Save as Raw V2Ray JSON</button>
          </div>
        </div>
      </div>

      <div id="groupsContainer"></div>

      <div class="card">
        <h2>All Proxy Profiles (<span id="profileCount">0</span>)</h2>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Protocol</th>
              <th>Server</th>
              <th>Latency</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="profilesTableBody"></tbody>
        </table>
      </div>
    </section>

    <section id="tab-subscriptions" style="display:none">
      <div class="card">
        <h2>Add Subscription (Remnawave / Mihomo YAML / Base64 / Happ)</h2>
        <div class="row">
          <input type="text" id="subName" style="width:200px" placeholder="Name (optional)">
          <input type="text" id="subUrl" class="grow" placeholder="https://... or happ://crypt...">
        </div>
        <div class="row">
          <input type="text" id="subUa" class="grow" placeholder="Custom User-Agent (optional, default Mihomo/1.19)">
          <label style="font-size:12.5px;color:var(--muted);display:flex;align-items:center;gap:5px;">
            <input type="checkbox" id="subHwid"> Send HWID
          </label>
          <button class="btn" id="addSubBtn">Add &amp; Fetch</button>
          <button class="btn secondary" id="refreshAllSubsBtn">Update All</button>
        </div>
      </div>
      <div class="card">
        <h2>Configured Subscriptions</h2>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>URL</th>
              <th>Profiles</th>
              <th>Updated</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="subsTableBody"></tbody>
        </table>
      </div>
    </section>

    <section id="tab-rules" style="display:none">
      <div class="card">
        <h2>Add Custom Routing Rule</h2>
        <div class="row">
          <input type="text" id="ruleName" style="width:180px" placeholder="Rule name">
          <input type="text" id="ruleDomains" class="grow" placeholder="Domains (e.g. domain:example.com, geosite:category-ru)">
          <input type="text" id="ruleIp" class="grow" placeholder="IP / CIDR (e.g. 192.168.0.0/16, geoip:ru)">
        </div>
        <div class="row">
          <input type="text" id="rulePort" style="width:140px" placeholder="Port (e.g. 443)">
          <select id="ruleNetwork">
            <option value="">TCP + UDP</option>
            <option value="tcp">TCP only</option>
            <option value="udp">UDP only</option>
          </select>
          <select id="ruleTarget">
            <option value="proxy">PROXY</option>
            <option value="direct">DIRECT</option>
            <option value="block">REJECT (Block)</option>
          </select>
          <button class="btn" id="addRuleBtn">Add Rule</button>
        </div>
      </div>
      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h2 style="margin:0">Active Rules (<span id="rulesCount">0</span>)</h2>
          <input type="text" id="ruleFilter" placeholder="Filter rules..." style="width:240px">
        </div>
        <table>
          <thead>
            <tr>
              <th>Type</th>
              <th>Matcher / Payload</th>
              <th>Target</th>
              <th></th>
            </tr>
          </thead>
          <tbody id="rulesTableBody"></tbody>
        </table>
      </div>
    </section>

    <section id="tab-settings" style="display:none">
      <div class="card">
        <h2>Daemon &amp; Network Settings (Mihomo Compatible)</h2>
        <div class="row">
          <label style="width:180px;color:var(--muted)">Mixed Port (HTTP+SOCKS5)</label>
          <input type="number" id="cfgMixedPort" min="0" max="65535">
        </div>
        <div class="row">
          <label style="width:180px;color:var(--muted)">TProxy Port (Linux)</label>
          <input type="number" id="cfgTproxyPort" min="0" max="65535">
        </div>
        <div class="row">
          <label style="width:180px;color:var(--muted)">Bind Address</label>
          <input type="text" id="cfgBindAddress" placeholder="0.0.0.0 or 192.168.1.10">
          <label style="font-size:13px;color:var(--muted);display:flex;align-items:center;gap:6px;">
            <input type="checkbox" id="cfgAllowLan"> Allow LAN
          </label>
        </div>
        <div class="row">
          <label style="width:180px;color:var(--muted)">Outbound Interface</label>
          <input type="text" id="cfgInterface" placeholder="e.g. eth0 (optional)">
        </div>
        <div class="row">
          <label style="width:180px;color:var(--muted)">Log Level</label>
          <select id="cfgLogLevel">
            <option value="debug">debug</option>
            <option value="info">info</option>
            <option value="warning">warning</option>
            <option value="error">error</option>
            <option value="silent">silent</option>
          </select>
          <label style="font-size:13px;color:var(--muted);display:flex;align-items:center;gap:6px;">
            <input type="checkbox" id="cfgIpv6"> Enable IPv6
          </label>
        </div>
        <div class="row" style="margin-top:14px">
          <button class="btn" id="saveSettingsBtn">Save &amp; Apply</button>
        </div>
      </div>
    </section>

    <section id="tab-logs" style="display:none">
      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h2 style="margin:0">Core &amp; Daemon Logs</h2>
          <button class="btn secondary sm" id="refreshLogsBtn">Refresh Logs</button>
        </div>
        <div class="logs-box" id="logsBox"></div>
      </div>
    </section>
  </main>
</div>

<div class="auth-modal" id="authModal">
  <div class="auth-box">
    <h2 style="margin-top:0;font-size:16px">Controller API Secret</h2>
    <p style="color:var(--muted);font-size:13px">Enter the <code>secret</code> configured on this Owenclave controller.</p>
    <div class="row">
      <input type="password" id="secretInput" class="grow" placeholder="Bearer secret">
    </div>
    <div class="row" style="justify-content:flex-end;margin-bottom:0">
      <button class="btn" id="saveSecretBtn">Connect</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
(() => {
  let state = null;
  let activeTab = 'proxies';

  const getSecret = () => localStorage.getItem('owenclave_secret') || '';
  const setSecret = (s) => localStorage.setItem('owenclave_secret', s);

  function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.style.display = 'block';
    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => { t.style.display = 'none'; }, 3200);
  }

  function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'text') node.textContent = v;
      else if (k === 'className') node.className = v;
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
      else node.setAttribute(k, v);
    }
    for (const child of children) {
      if (child == null) continue;
      node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return node;
  }

  async function api(path, options = {}) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    const secret = getSecret();
    if (secret) headers['Authorization'] = 'Bearer ' + secret;
    const res = await fetch(path, Object.assign({}, options, { headers }));
    if (res.status === 401) {
      document.getElementById('authModal').style.display = 'flex';
      throw new Error('Authentication required');
    }
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new Error(data.message || data.error || ('HTTP ' + res.status));
    return data;
  }

  async function loadState() {
    try {
      state = await api('/api/state');
      document.getElementById('authModal').style.display = 'none';
      render();
    } catch (e) {
      if (e.message !== 'Authentication required') toast('Error: ' + e.message);
    }
  }

  function render() {
    if (!state) return;
    document.getElementById('versionLabel').textContent = 'v' + state.version + ' · Mihomo API';
    const dot = document.getElementById('coreDot');
    dot.className = 'dot' + (state.running ? ' ok' : '');
    document.getElementById('activeProfileLabel').textContent =
      state.activeProfile ? state.activeProfile.name : (state.configs.mode === 'direct' ? 'DIRECT' : 'No active profile');
    document.getElementById('activeProtocolBadge').textContent =
      state.activeProfile ? state.activeProfile.protocol : state.configs.mode.toUpperCase();
    document.getElementById('inboundBadge').textContent =
      'mixed ' + state.configs['bind-address'] + ':' + state.configs['mixed-port'] +
      (state.configs['tproxy-port'] > 0 ? (' · tproxy :' + state.configs['tproxy-port']) : '');

    document.querySelectorAll('#modeSwitch button').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.mode === state.configs.mode);
    });

    // Groups
    const groupsContainer = document.getElementById('groupsContainer');
    groupsContainer.replaceChildren();
    for (const g of (state.groups || [])) {
      const chips = el('div', { className: 'chips' });
      for (const choice of g.all) {
        chips.appendChild(el('button', {
          className: 'chip' + (g.now === choice ? ' active' : ''),
          text: choice,
          onClick: async () => {
            try {
              await api('/proxies/' + encodeURIComponent(g.name), {
                method: 'PUT',
                body: JSON.stringify({ name: choice })
              });
              toast(g.name + ' -> ' + choice);
              await loadState();
            } catch (err) { toast(err.message); }
          }
        }));
      }
      groupsContainer.appendChild(el('div', { className: 'card' }, [
        el('h2', { text: g.name + ' (' + g.type + ' · ' + g.now + ')' }),
        chips
      ]));
    }

    // Profiles table
    document.getElementById('profileCount').textContent = String(state.profiles.length);
    const pBody = document.getElementById('profilesTableBody');
    pBody.replaceChildren();
    for (const p of state.profiles) {
      const protoLower = (p.protocol || '').toLowerCase();
      const protoBadge = el('span', {
        className: 'badge ' + (protoLower === 'https' ? 'https' : (protoLower === 'http' ? 'http' : '')),
        text: p.protocol
      });
      const actions = el('div', { className: 'row', style: 'margin:0;gap:6px' }, [
        el('button', {
          className: 'btn sm' + (p.selected ? '' : ' secondary'),
          text: p.selected ? 'Active' : 'Use',
          onClick: async () => {
            await api('/api/profiles/select', { method: 'POST', body: JSON.stringify({ id: p.id }) });
            await loadState();
          }
        }),
        p.shareUri ? el('button', {
          className: 'btn secondary sm',
          text: 'Copy URI',
          onClick: async () => {
            await navigator.clipboard.writeText(p.shareUri);
            toast('Copied share URI');
          }
        }) : null,
        p.removable ? el('button', {
          className: 'btn danger sm',
          text: 'Delete',
          onClick: async () => {
            await api('/api/profiles/' + encodeURIComponent(p.id), { method: 'DELETE' });
            toast('Removed ' + p.name);
            await loadState();
          }
        }) : null
      ]);
      pBody.appendChild(el('tr', {}, [
        el('td', { text: (p.selected ? '● ' : '') + p.name }),
        el('td', {}, [protoBadge]),
        el('td', { className: 'mono', text: p.address }),
        el('td', { className: 'mono', text: p.delay ? (p.delay + ' ms') : '-' }),
        el('td', {}, [actions])
      ]));
    }

    // Subscriptions
    const sBody = document.getElementById('subsTableBody');
    sBody.replaceChildren();
    for (const s of state.subscriptions) {
      sBody.appendChild(el('tr', {}, [
        el('td', { text: s.name }),
        el('td', { className: 'mono', text: s.url }),
        el('td', { text: String(s.profileCount) }),
        el('td', { text: s.lastUpdated > 0 ? new Date(s.lastUpdated).toLocaleString() : 'never' }),
        el('td', {}, [
          el('div', { className: 'row', style: 'margin:0;gap:6px' }, [
            el('button', {
              className: 'btn secondary sm',
              text: 'Refresh',
              onClick: async () => {
                toast('Refreshing ' + s.name + '...');
                await api('/api/subscriptions/' + encodeURIComponent(s.id) + '/refresh', { method: 'POST' });
                toast('Updated ' + s.name);
                await loadState();
              }
            }),
            el('button', {
              className: 'btn danger sm',
              text: 'Delete',
              onClick: async () => {
                await api('/api/subscriptions/' + encodeURIComponent(s.id), { method: 'DELETE' });
                await loadState();
              }
            })
          ])
        ])
      ]));
    }

    // Rules
    renderRules();

    // Settings
    document.getElementById('cfgMixedPort').value = state.configs['mixed-port'];
    document.getElementById('cfgTproxyPort').value = state.configs['tproxy-port'];
    document.getElementById('cfgBindAddress').value = state.configs['bind-address'];
    document.getElementById('cfgAllowLan').checked = !!state.configs['allow-lan'];
    document.getElementById('cfgInterface').value = state.configs['interface-name'] || '';
    document.getElementById('cfgLogLevel').value = state.configs['log-level'] || 'info';
    document.getElementById('cfgIpv6').checked = !!state.configs['ipv6'];

    // Logs
    const logsBox = document.getElementById('logsBox');
    logsBox.replaceChildren();
    for (const item of (state.logs || [])) {
      logsBox.appendChild(el('div', {
        className: 'log-line log-' + (item.type || 'info'),
        text: '[' + item.time + '] [' + item.type + '] ' + item.payload
      }));
    }
    logsBox.scrollTop = logsBox.scrollHeight;
  }

  function renderRules() {
    if (!state) return;
    const q = (document.getElementById('ruleFilter').value || '').toLowerCase();
    const filtered = (state.rules || []).filter(r =>
      !q || r.type.toLowerCase().includes(q) || r.payload.toLowerCase().includes(q) || r.proxy.toLowerCase().includes(q)
    );
    document.getElementById('rulesCount').textContent = String(filtered.length);
    const rBody = document.getElementById('rulesTableBody');
    rBody.replaceChildren();
    for (const r of filtered.slice(0, 300)) {
      rBody.appendChild(el('tr', {}, [
        el('td', { className: 'mono', text: r.type }),
        el('td', { className: 'mono', text: r.payload }),
        el('td', {}, [el('span', { className: 'badge', text: r.proxy })]),
        el('td', {}, [
          r.userDefined ? el('button', {
            className: 'btn danger sm',
            text: 'Delete',
            onClick: async () => {
              await api('/api/rules/' + encodeURIComponent(r.id), { method: 'DELETE' });
              await loadState();
            }
          }) : null
        ])
      ]));
    }
  }

  // Navigation
  document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      activeTab = btn.dataset.tab;
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.toggle('active', b === btn));
      ['proxies', 'subscriptions', 'rules', 'settings', 'logs'].forEach(t => {
        document.getElementById('tab-' + t).style.display = (t === activeTab) ? 'block' : 'none';
      });
    });
  });

  // HTTP / HTTPS toggle
  document.getElementById('httpProtoSelect').addEventListener('change', (e) => {
    const isHttps = e.target.value === 'https';
    document.getElementById('httpsTlsRow').style.display = isHttps ? 'flex' : 'none';
    document.getElementById('addHttpProxyBtn').textContent = isHttps ? 'Add HTTPS Proxy' : 'Add HTTP Proxy';
    const portEl = document.getElementById('httpPort');
    if (isHttps && portEl.value === '8080') portEl.value = '443';
    if (!isHttps && portEl.value === '443') portEl.value = '8080';
  });

  document.getElementById('addHttpProxyBtn').addEventListener('click', async () => {
    try {
      const tls = document.getElementById('httpProtoSelect').value === 'https';
      await api('/api/profiles/http', {
        method: 'POST',
        body: JSON.stringify({
          tls,
          name: document.getElementById('httpName').value,
          host: document.getElementById('httpHost').value,
          port: Number(document.getElementById('httpPort').value),
          username: document.getElementById('httpUser').value,
          password: document.getElementById('httpPass').value,
          sni: document.getElementById('httpSni').value,
          allowInsecure: document.getElementById('httpInsecure').checked,
          udpOverTcp: document.getElementById('httpUot').checked
        })
      });
      document.getElementById('httpName').value = '';
      document.getElementById('httpHost').value = '';
      document.getElementById('httpPass').value = '';
      toast('Added ' + (tls ? 'HTTPS' : 'HTTP') + ' proxy');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.getElementById('importLinksBtn').addEventListener('click', async () => {
    try {
      const text = document.getElementById('importText').value;
      const res = await api('/api/profiles/import', { method: 'POST', body: JSON.stringify({ text, raw: false }) });
      document.getElementById('importText').value = '';
      toast('Imported ' + res.imported + ' profile(s)');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.getElementById('importRawBtn').addEventListener('click', async () => {
    try {
      const text = document.getElementById('importText').value;
      await api('/api/profiles/import', { method: 'POST', body: JSON.stringify({ text, raw: true }) });
      document.getElementById('importText').value = '';
      toast('Added raw config profile');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.querySelectorAll('#modeSwitch button').forEach(btn => {
    btn.addEventListener('click', async () => {
      try {
        await api('/configs', { method: 'PATCH', body: JSON.stringify({ mode: btn.dataset.mode }) });
        toast('Mode: ' + btn.dataset.mode);
        await loadState();
      } catch (e) { toast(e.message); }
    });
  });

  document.getElementById('pingActiveBtn').addEventListener('click', async () => {
    try {
      document.getElementById('latencyBadge').textContent = 'ping: ...';
      const target = (state && state.activeProfile && state.activeProfile.name) || 'GLOBAL';
      const res = await api('/proxies/' + encodeURIComponent(target) + '/delay?timeout=5000');
      document.getElementById('latencyBadge').textContent = 'ping: ' + res.delay + ' ms';
      await loadState();
    } catch (e) {
      document.getElementById('latencyBadge').textContent = 'ping: timeout';
      toast('Ping failed: ' + e.message);
    }
  });

  document.getElementById('reloadBtn').addEventListener('click', async () => {
    await api('/api/reload', { method: 'POST', body: '{}' });
    toast('Core reloaded');
    await loadState();
  });

  document.getElementById('tokenBtn').addEventListener('click', () => {
    document.getElementById('secretInput').value = getSecret();
    document.getElementById('authModal').style.display = 'flex';
  });

  document.getElementById('saveSecretBtn').addEventListener('click', async () => {
    setSecret(document.getElementById('secretInput').value.trim());
    await loadState();
  });

  document.getElementById('addSubBtn').addEventListener('click', async () => {
    try {
      toast('Fetching subscription...');
      await api('/api/subscriptions', {
        method: 'POST',
        body: JSON.stringify({
          name: document.getElementById('subName').value,
          url: document.getElementById('subUrl').value,
          userAgent: document.getElementById('subUa').value,
          sendHwid: document.getElementById('subHwid').checked
        })
      });
      document.getElementById('subName').value = '';
      document.getElementById('subUrl').value = '';
      toast('Subscription added');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.getElementById('refreshAllSubsBtn').addEventListener('click', async () => {
    toast('Updating all subscriptions...');
    await api('/api/subscriptions/refresh-all', { method: 'POST', body: '{}' });
    toast('Subscriptions updated');
    await loadState();
  });

  document.getElementById('addRuleBtn').addEventListener('click', async () => {
    try {
      await api('/api/rules', {
        method: 'POST',
        body: JSON.stringify({
          name: document.getElementById('ruleName').value,
          domains: document.getElementById('ruleDomains').value,
          ip: document.getElementById('ruleIp').value,
          port: document.getElementById('rulePort').value,
          network: document.getElementById('ruleNetwork').value,
          target: document.getElementById('ruleTarget').value
        })
      });
      document.getElementById('ruleName').value = '';
      document.getElementById('ruleDomains').value = '';
      document.getElementById('ruleIp').value = '';
      document.getElementById('rulePort').value = '';
      toast('Rule added');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.getElementById('ruleFilter').addEventListener('input', renderRules);

  document.getElementById('saveSettingsBtn').addEventListener('click', async () => {
    try {
      await api('/configs', {
        method: 'PATCH',
        body: JSON.stringify({
          'mixed-port': Number(document.getElementById('cfgMixedPort').value),
          'tproxy-port': Number(document.getElementById('cfgTproxyPort').value),
          'bind-address': document.getElementById('cfgBindAddress').value,
          'allow-lan': document.getElementById('cfgAllowLan').checked,
          'interface-name': document.getElementById('cfgInterface').value,
          'log-level': document.getElementById('cfgLogLevel').value,
          'ipv6': document.getElementById('cfgIpv6').checked
        })
      });
      toast('Settings applied');
      await loadState();
    } catch (e) { toast(e.message); }
  });

  document.getElementById('refreshLogsBtn').addEventListener('click', loadState);

  loadState();
  setInterval(() => { if (document.visibilityState === 'visible') loadState(); }, 8000);
})();
</script>
</body>
</html>
""".trimIndent()

}

