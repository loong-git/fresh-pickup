#!/usr/bin/env node
/**
 * 十期 P10-2 · 四端 dev server 探活脚本
 * 用法：node tools/verify/probe.mjs [端口...]（缺省扫 3001/5173-5179）
 * 口径（交接文档 §2/§8）：按页面 <title> 复核端身份，别背端口表；后端 3001 看响应 envelope。
 * 实现注记（2026-11-05 实测）：本机 Node v24.13.0 下 fetch+AbortSignal.timeout 退出时触发
 * libuv 断言（src\win\async.c UV_HANDLE_CLOSING，exit 127）——探活分两步：net.connect 探活
 * （干净退出）+ http.get 读 title（老回调式，不挂 signal）。
 */
import net from 'node:net';
import http from 'node:http';

const PORTS = process.argv.slice(2).length ? process.argv.slice(2).map(Number) : [3001, 5173, 5174, 5175, 5177, 5178, 5179];
const EXPECT = { '生鲜配送': 'C端', '生鲜商家端|商家登录': '商家端', '生鲜主理人端|主理人登录': '主理人端', '生鲜运营端': '运营端' };

function tcpProbe(port) {
  return new Promise((resolve) => {
    const s = net.connect({ port, host: '127.0.0.1', timeout: 2000 });
    s.on('connect', () => { s.destroy(); resolve(true); });
    s.on('timeout', () => { s.destroy(); resolve(false); });
    s.on('error', () => resolve(false));
  });
}

function httpGet(port, path = '/') {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path, timeout: 2500 }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; if (body.length > 65536) req.destroy(); });
      res.on('end', () => resolve({ status: res.statusCode, body }));
      res.on('error', () => resolve(null));
    });
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
  });
}

let fail = 0;
const lines = [];
for (const port of PORTS) {
  const up = await tcpProbe(port);
  if (!up) { lines.push(`:${port} DOWN`); continue; }
  const res = await httpGet(port);
  const m = res ? /<title>([^<]*)<\/title>/.exec(res.body) : null;
  const title = m ? m[1] : '(无 title)';
  const role = Object.entries(EXPECT).find(([k]) => new RegExp(k).test(title))?.[1] ?? '未知端';
  lines.push(`:${port} UP  ${res ? res.status : '?'}  "${title}"  → ${role}`);
  if (port === 3001) {
    // 后端身份二次确认：任意 /api 路径应回 JSON envelope（code/message）而非 HTML
    const api = await httpGet(port, '/api/__probe__');
    let envOk = false;
    if (api) { try { const j = JSON.parse(api.body); envOk = typeof j.code === 'number' && 'message' in j; } catch { /* 非 JSON */ } }
    if (envOk) lines.push('  后端 envelope 校验 ✓ (code/message 结构)');
    else { lines.push('  后端 envelope 校验 ✗（:3001 有监听但 /api 响应非 envelope）'); fail++; }
  }
}
console.log(lines.join('\n'));
const ups = lines.filter((l) => l.includes(' UP ')).length;
console.log(`--- ${ups}/${PORTS.length} 端口存活`);
process.exit(fail ? 1 : 0);
