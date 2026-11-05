#!/usr/bin/env node
/**
 * 十期 P10-2 · 双主题切换 CDP 实测脚本（运营端/商家端/主理人端通用）
 * 用法：node tools/verify/theme-check.mjs <port> <storageKey> [admin|uniapp]
 * 口径（P9 插曲登记）：合成 click 不可信——本脚本走 Chrome DevTools Protocol
 * Input.dispatchMouseEvent 真实鼠标事件；dev server 需以 --remote-debugging-port 启动才有 CDP 面。
 * 输出：双向切换+持久化各步读数，任何一步不符即 exit 1。
 *
 * 前置：被测 dev server 带调试端口，如：
 *   node front-admin/node_modules/vite/bin/vite.js --port 5177 --strictPort（默认无 CDP）
 *   实测姿势 A（本仓主力）：ZCode 内置浏览器（browser-use）自带 CDP，脚本箱外的目视/交互走它；
 *   本脚本为姿势 B：直连裸 Chrome 的 CDP——
 *   "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --remote-debugging-port=9333 about:blank
 */
import http from 'node:http';
import crypto from 'node:crypto';

const [, , portArg, keyArg, flavorArg] = process.argv;
if (!portArg || !keyArg) {
  console.log('用法：node tools/verify/theme-check.mjs <port> <storageKey> [admin|uniapp]');
  console.log('  port       被测端 dev server 端口（如 5177）');
  console.log('  storageKey 主题键（运营端 admin_theme / 商家主理人端 app_theme）');
  console.log('  flavor     admin（默认，运营端 origin 直连）| uniapp（hash 路由，先等 #/ 渲染）');
  process.exit(2);
}
const PORT = Number(portArg);
const KEY = keyArg;
const FLAVOR = flavorArg || 'admin';
const CDP_HTTP = 9333; // 调试端口约定（chrome --remote-debugging-port）

function cdpJson(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: CDP_HTTP, path }, (res) => {
      let b = ''; res.on('data', (d) => (b += d)); res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

async function main() {
  // 1. 找到目标 tab（title 或 url 匹配被测端口）
  const targets = await cdpJson('/json/list');
  const tab = targets.find((t) => t.type === 'page' && (t.url.includes(`localhost:${PORT}`) || t.url.includes(`127.0.0.1:${PORT}`)));
  if (!tab) { console.log(`✗ 未找到 localhost:${PORT} 的页面 tab；先在浏览器打开被测端`); process.exit(1); }
  console.log(`目标 tab: ${tab.title} ${tab.url}`);

  // 2. WebSocket 连接（node:crypto 升级头手写太繁琐，用内置 WebSocket——Node 22+ 全局可用）
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let seq = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
  };
  const send = (method, params = {}) => new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('页面求值异常: ' + JSON.stringify(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text));
    return r.result?.result?.value;
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // 3. 真实鼠标点击（mousePressed+mouseReleased，P9 插曲口径）
  const realClick = async (x, y) => {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  };

  let fails = 0;
  const expect = (name, cond, detail) => {
    console.log(`${cond ? '✓' : '✗'} ${name}${detail ? '  ' + detail : ''}`);
    if (!cond) fails++;
  };

  if (FLAVOR === 'uniapp') { await evaluate(`location.hash`); await sleep(1200); }

  // 3b. 登录态预置（交接 §7.4 姿势：JWT 自签 HS256，key=SHA-256('dev-secret-please-change')）。
  // topbar 主题钮只在登录后出现，未登录先注入 admin_token 再刷新。
  const crypto = await import('node:crypto');
  const key = crypto.createHash('sha256').update('dev-secret-please-change').digest();
  const b64u = (b) => Buffer.from(b).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  // sub 来源：环境变量 THEME_CHECK_SUB 可覆盖（admin=3/demo，uniapp 缺省 34=商家演示号；主理人传 33）。
  const flavorSub = process.env.THEME_CHECK_SUB || '34';
  const claims = FLAVOR === 'admin'
    ? { sub: '3', iat: now, exp: now + 3600, typ: 'admin', role: 'admin', uname: 'demo' }
    : { sub: flavorSub, iat: now, exp: now + 3600 };
  const header = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64u(JSON.stringify(claims));
  const sig = crypto.createHmac('sha256', key).update(`${header}.${payload}`).digest('base64url');
  const jwt = `${header}.${payload}.${sig}`;
  const tokenKey = FLAVOR === 'admin' ? 'admin_token' : 'partner_token';
  // 注 token（sub 必须真实存在于 admin_user/user 表——后端查库定角色，401 链路会清伪造 sub 的态）。
  await evaluate(`localStorage.setItem(${JSON.stringify(tokenKey)}, ${JSON.stringify(jwt)})`);
  await evaluate(`location.reload()`); await sleep(FLAVOR === 'uniapp' ? 3000 : 1800);
  const where = await evaluate(`({path: location.pathname, hash: location.hash, hasToken: !!localStorage.getItem(${JSON.stringify(tokenKey)})})`);
  if (!where.hasToken) { console.log('✗ 注入的 token 被 401 链路清除（sub 不在库里或 claims 不符）'); process.exit(1); }
  if (where.path === '/login' || where.hash === '#/') { console.log('⚠ 仍停在登录页 path=' + where.path + where.hash); }

  // 4. 基线：清档应为浅色。注意顺序：清主题档但**保登录态**（清档后 reload，若被路由守卫
  //    送回 /login，再注入 token 回 /overview——token 注入在步骤 3b 已做，此处只是防 401 清除）。
  await evaluate(`localStorage.removeItem(${JSON.stringify(KEY)})`);
  await evaluate(`location.reload()`); await sleep(FLAVOR === 'uniapp' ? 2500 : 1500);
  const base = await evaluate(`({cls: document.documentElement.className, stored: localStorage.getItem(${JSON.stringify(KEY)})})`);
  expect('清档后浅色默认（无 dark class）', base.cls !== 'dark' && !base.cls.includes('dark'), `cls="${base.cls}"`);

  // 5. 找主题钮/主题 cell 坐标 → 真实点击切深色
  const box = await evaluate(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find(b => (b.textContent||'').includes('主题'));
    if (btn) { const r = btn.getBoundingClientRect(); return { x: r.x + r.width/2, y: r.y + r.height/2 }; }
    const cell = Array.from(document.querySelectorAll('*')).find(e => String(e.className||'').includes('cell') && (e.textContent||'').trim().startsWith('主题'));
    if (cell) { const r = cell.getBoundingClientRect(); return { x: r.x + r.width/2, y: r.y + r.height/2 }; }
    return null;
  })()`);
  if (!box) { console.log('✗ 未找到主题切换钮/cell'); process.exit(1); }
  await realClick(Math.round(box.x), Math.round(box.y)); await sleep(800);
  const dark = await evaluate(`({cls: document.documentElement.className, stored: localStorage.getItem(${JSON.stringify(KEY)}), bg: getComputedStyle(document.body).backgroundColor})`);
  expect('真实点击→深色 class', dark.cls === 'dark', `cls="${dark.cls}"`);
  expect('深色档已持久化', dark.stored === 'dark', `stored="${dark.stored}"`);

  // 6. 再点回浅色（双向）
  await realClick(Math.round(box.x), Math.round(box.y)); await sleep(800);
  const light = await evaluate(`({cls: document.documentElement.className, stored: localStorage.getItem(${JSON.stringify(KEY)})})`);
  expect('再点→回浅色', !light.cls.includes('dark'), `cls="${light.cls}"`);
  expect('浅色档已持久化', light.stored === 'light', `stored="${light.stored}"`);

  // 7. 刷新持久化双向
  await evaluate(`localStorage.setItem(${JSON.stringify(KEY)}, 'dark')`);
  await evaluate(`location.reload()`); await sleep(FLAVOR === 'uniapp' ? 2500 : 1500);
  const rd = await evaluate(`({cls: document.documentElement.className, stored: localStorage.getItem(${JSON.stringify(KEY)})})`);
  expect('刷新后保持深色', rd.cls === 'dark', `cls="${rd.cls}"`);
  await evaluate(`localStorage.setItem(${JSON.stringify(KEY)}, 'light')`);
  await evaluate(`location.reload()`); await sleep(FLAVOR === 'uniapp' ? 2500 : 1500);
  const rl = await evaluate(`({cls: document.documentElement.className, stored: localStorage.getItem(${JSON.stringify(KEY)})})`);
  expect('刷新后保持浅色', !rl.cls.includes('dark'), `cls="${rl.cls}"`);

  ws.close();
  console.log(`--- ${fails === 0 ? '全部通过' : fails + ' 项不符'}`);
  process.exit(fails ? 1 : 0);
}

main().catch((e) => { console.error('✗', e.message); process.exit(1); });
