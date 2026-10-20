#!/usr/bin/env bash
# =====================================================================
# 一键冒烟脚本（T-M4-06 工程收尾）：登录 → 下单 → 查询本人订单 → 取消
# 覆盖链路：验证码登录签发 JWT → Bearer 鉴权下单（服务端计价 + clientRequestId 幂等号）
#           → 订单按 token userId 归属分页查询 → 取消订单（状态机校验 + 库存回补）。
# 所有接口响应为统一信封 R{code,data,message}（HTTP 恒 200），逐步断言 body code==200。
#
# 用法：
#   1) 首次赋予可执行权限（Linux/macOS/CI 执行一次）：chmod +x scripts/smoke.sh
#   2) 运行（三选一，优先级：位置参数 > 环境变量 > 默认值）：
#      bash scripts/smoke.sh                               # 默认 http://localhost:3001
#      bash scripts/smoke.sh http://localhost:3001         # 位置参数指定
#      BASE_URL=http://xxx:3001 bash scripts/smoke.sh      # 环境变量指定
#
# 说明：
#   - 每次运行随机生成新手机号（后端静默注册新用户），规避下单限流
#     （POST /api/orders 3 次/分钟/用户）与历史订单干扰，可重复执行
#   - dev 环境固定验证码 123456（backEnd application-dev.yml: auth.dev-code）
#   - JSON 解析仅用 grep/sed，不依赖 jq（CI 最小依赖）
#   - 任一步骤失败：打印步骤名与响应报文，退出码 1
# =====================================================================
set -u   # 引用未定义变量立即报错；不用 -e，失败统一走 assert_ok 自定义文案输出

BASE_URL="${1:-${BASE_URL:-http://localhost:3001}}"
DEV_CODE="123456"                 # dev 固定验证码
CURL="curl -sS --max-time 15"     # 统一 curl 参数：静默进度条、错误落到 stderr、15s 超时防挂死

# 随机手机号：138 + 时间戳后 6 位（补零）+ 2 位随机数 = 恒 11 位，匹配 1[3-9]\d{9}（LoginDTO/OrderCreateDTO 同规则）
TS=$(date +%s)
PHONE=$(printf "138%06d%02d" $((TS % 1000000)) $((RANDOM % 100)))
# 幂等下单标识（OrderCreateDTO.clientRequestId，≤64 位）
CLIENT_REQUEST_ID="smoke-${TS}-${RANDOM}"

echo "== 生鲜后端一键冒烟（${BASE_URL}） =="
echo "   手机号: ${PHONE}   幂等号: ${CLIENT_REQUEST_ID}"

# 断言函数：统一信封 code==200 才放行，否则打印响应并退出 1
assert_ok() {
  local step="$1" resp="$2"
  if ! echo "$resp" | grep -q '"code":200'; then
    echo "[FAIL] ${step} —— 响应 code != 200"
    echo "       ${resp}"
    exit 1
  fi
  echo "[OK]   ${step}"
}

# ---------- 步骤 1：登录拿 token（发送验证码 + 验证码登录） ----------
resp=$($CURL -X POST "$BASE_URL/api/auth/send-code" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\"}")
assert_ok "步骤1a 发送验证码 POST /api/auth/send-code" "$resp"

resp=$($CURL -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"code\":\"$DEV_CODE\"}")
assert_ok "步骤1b 验证码登录 POST /api/auth/login" "$resp"
# 登录 data = {token, user:{id, phone}}，提取 JWT
TOKEN=$(echo "$resp" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "${TOKEN:-}" ]; then
  echo "[FAIL] 步骤1b 未从登录响应提取到 token"
  echo "       ${resp}"
  exit 1
fi

# ---------- 步骤 2：下单（Bearer 鉴权 + clientRequestId 幂等号 + 服务端计价） ----------
# 前置：取 GET /api/dishes 第一个商品做下单素材（Dish 实体首字段即 Long id，
# grep -o 逐个输出全部 "id":N 匹配、head -1 取首个即首商品）
resp=$($CURL "$BASE_URL/api/dishes")
DISH_ID=$(echo "$resp" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "${DISH_ID:-}" ]; then
  echo "[FAIL] 步骤2 前置准备：GET /api/dishes 未提取到商品 id"
  echo "       ${resp}"
  exit 1
fi
# 前置：取 GET /api/stores 第一个自提点——F-80 起 OrderCreateDTO.storeId 必填，不传直接 400
resp=$($CURL "$BASE_URL/api/stores")
STORE_ID=$(echo "$resp" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "${STORE_ID:-}" ]; then
  echo "[FAIL] 步骤2 前置准备：GET /api/stores 未提取到自提点 id"
  echo "       ${resp}"
  exit 1
fi

resp=$($CURL -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"clientRequestId\":\"$CLIENT_REQUEST_ID\",\"phone\":\"$PHONE\",\"storeId\":$STORE_ID,\"items\":[{\"dishId\":$DISH_ID,\"quantity\":1}]}")
assert_ok "步骤2 下单 POST /api/orders (dishId=${DISH_ID}, storeId=${STORE_ID})" "$resp"
# 订单 id 为雪花字符串（Order 实体首字段，取响应首个 "id":"..."）
ORDER_ID=$(echo "$resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "${ORDER_ID:-}" ]; then
  echo "[FAIL] 步骤2 未从下单响应提取到订单 id"
  echo "       ${resp}"
  exit 1
fi

# ---------- 步骤 3：查询本人订单（按 token userId 过滤，验证本单与幂等号可见） ----------
resp=$($CURL -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/orders?pageNum=1&pageSize=10")
assert_ok "步骤3 查询本人订单 GET /api/orders" "$resp"
if ! echo "$resp" | grep -q "\"id\":\"$ORDER_ID\""; then
  echo "[FAIL] 步骤3 订单列表未见本次订单 ${ORDER_ID}"
  exit 1
fi
if ! echo "$resp" | grep -q "\"clientRequestId\":\"$CLIENT_REQUEST_ID\""; then
  echo "[FAIL] 步骤3 订单列表未见本次幂等号 ${CLIENT_REQUEST_ID}"
  exit 1
fi

# ---------- 步骤 4：取消订单（仅本人 pending_pickup 可取消，服务端回补库存） ----------
resp=$($CURL -X POST -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/orders/$ORDER_ID/cancel")
assert_ok "步骤4 取消订单 POST /api/orders/${ORDER_ID}/cancel" "$resp"

echo "== 冒烟通过：登录/下单/查询/取消 4 步全链路 OK =="
exit 0
