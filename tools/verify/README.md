# tools/verify — 十期 P10-2 验收脚本箱

> 2026-11-05。把交接文档 §7/§8 散落的验收姿势固化为可执行脚本。全部 Node 直跑（无 bash 依赖，
> 规避 Windows spawn PATH 坑）。

## probe.mjs — 四端探活

```bash
node tools/verify/probe.mjs            # 缺省扫 3001/5173-5179
node tools/verify/probe.mjs 3001 5177  # 指定端口
```

- 按 `<title>` 复核端身份（生鲜配送/生鲜商家端/生鲜主理人端/生鲜运营端），**别背端口表**（随重启漂移）；
- 后端 :3001 二次校验 `/api/__probe__` 返回 JSON envelope（code/message 结构）；
- 实现注记：本机 Node v24.13.0 的 `fetch`+`AbortSignal.timeout` 进程退出时触发 libuv 断言
  （`src\win\async.c` UV_HANDLE_CLOSING，exit 127）——故探活走 `net.connect`、读 title 走
  `http.get` 回调式，干净退出。

## theme-check.mjs — 双主题切换 CDP 实测（三端通用）

```bash
# 前置：起一个 headless Chrome 调试实例（CDP 端口约定 9333）
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new \
  --remote-debugging-port=9333 --user-data-dir="$TEMP/chrome-cdp-p10" about:blank &

# 在该 Chrome 里打开被测页（PUT 是 /json/new 的唯一允许动词）
curl -X PUT "http://127.0.0.1:9333/json/new?http://localhost:5177/login"
curl -X PUT "http://127.0.0.1:9333/json/new?http://localhost:5173/"
curl -X PUT "http://127.0.0.1:9333/json/new?http://localhost:5174/"

# 跑七项断言（双向切换 + 持久化 + 刷新保持 ×2）
node tools/verify/theme-check.mjs 5177 admin_theme admin
node tools/verify/theme-check.mjs 5173 app_theme uniapp
THEME_CHECK_SUB=33 node tools/verify/theme-check.mjs 5174 app_theme uniapp   # 主理人端
```

- **真实鼠标事件**：`Input.dispatchMouseEvent` mousePressed+mouseReleased（P9 插曲口径：合成
  `el.click()` 不触发 uni-app/Vue 绑定，不可信）；
- 登录态预置：JWT 自签（HS256，key=SHA-256('dev-secret-please-change')；claims 三件套
  `typ=admin/role=admin/uname` 仅运营端；sub 必须真实在库——admin=demo(id 3)、商家=34、
  主理人=33，用 `THEME_CHECK_SUB` 覆盖；伪造 sub 会被后端查库 401 链路清除）；
- 七项断言：清档浅色默认 → 真实点击→深色 class+storage → 再点→回浅色 → storage=dark 刷新保持
  → storage=light 刷新保持；任何一项不符 exit 1。

## 实测记录（2026-11-05，P10-2 交付当轮）

| 端 | 命令 | 结果 |
|---|---|---|
| 运营端 :5177 | `theme-check.mjs 5177 admin_theme admin` | 7/7 ✓ |
| 商家端 :5173 | `theme-check.mjs 5173 app_theme uniapp` | 7/7 ✓ |
| 主理人端 :5174 | `THEME_CHECK_SUB=33 … 5174 app_theme uniapp` | 7/7 ✓ |
| 探活 | `probe.mjs` | 3001 envelope ✓ / 5173 商家 / 5174 主理人 / 5177 运营 ✓ |

与 ZCode 内置浏览器（browser-use）验收互为独立姿势：内置浏览器跑目视与交互流（P9-fix 当轮），
本脚本箱跑可重复断言（十期起每轮主题相关改动后跑一遍）。
