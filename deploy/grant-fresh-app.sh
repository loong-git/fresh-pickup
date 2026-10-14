#!/usr/bin/env bash
# ============================================================================
# F-43 | 应用运行账号「最小权限」授予脚本
#
# 作用：在目标 MySQL 上创建/复位应用账号（默认 'fresh_app'@'localhost'），
#       仅授予 <DB_NAME>.* 的 SELECT / INSERT / UPDATE / DELETE 四项权限，
#       绝不给任何 DDL（CREATE / ALTER / DROP / INDEX / TRUNCATE）、不给
#       GRANT OPTION、不给 TRIGGER、不给任何全局权限（除隐式 USAGE ON *.*）。
#
# 权限为什么恰好是这四项（含 DELETE）：
#   - DELETE 为运行必需，非疏漏：运营端驳回评价走物理删除——
#       backEnd/src/main/java/com/fresh/service/impl/AdminServiceImpl.java:226
#       reviewMapper.deleteById(reviewId) → MyBatis-Plus 生成 DELETE FROM review …。
#   - DDL 一律不给：全仓运行时无任何建表/改表语句（grep 运行时代码无
#       "ALTER/CREATE/DROP TABLE" 字面量），建库/迁移全部由人工执行
#       backEnd/sql/*.sql（init.sql + m3~m19-migration.sql）完成。应用账号无需 DDL。
#
# 幂等策略（本脚本选定：先 DROP USER IF EXISTS 再 CREATE + GRANT）：
#   每次执行都产出逐字节相同的终态——账号存在、口令 = FRESH_APP_PASSWORD、
#   且【仅】持有 <DB_NAME>.* 的四项权限。理由：
#     - 'DROP USER IF EXISTS' 使首次（尚不存在该账号）执行也不报错 → 可反复重跑；
#     - DROP 会连带清除该账号在【其它库】上可能残留的历史授权，严格回到「最小权限」，
#       不留下 REVOKE 复位时「无授权却 REVOKE → ERROR 1141」那份额外容错分支。
#   代价：复位瞬间会中断该账号现有连接（属预期的「运维复位」语义）。
#
# 口令纪律（沿用本仓 mysql --defaults-extra-file 姿势；口令绝不进命令行/文件/回显）：
#   - 新账号口令：仅从环境变量 FRESH_APP_PASSWORD 读取；未设置即报错退出。它只以
#     shell 变量存在于内存，经 stdin（heredoc）流入 SQL，绝不写盘、绝不出现在 argv、绝不 echo。
#   - root（执行建号/授权的高权限）连接：本脚本自行生成【临时】defaults 文件
#     （mktemp + chmod 600 + trap 删除），root 口令来自环境变量 MYSQL_ROOT_PASSWORD，
#     或从既有 defaults 文件 MYSQL_ROOT_DEFAULTS_FILE 解析；同样不进 argv、不回显。
#   - 实测偏差记录：本机 MySQL 8.0.36 客户端把 --defaults-extra-file 当作【必须最先】
#     的选项，与 --no-defaults 同用会报 unknown variable；故本脚本仅用 --defaults-extra-file
#     单独形式（与本仓既有 mysql --defaults-extra-file=... 姿势一致）。
#
# 用法：
#   export FRESH_APP_PASSWORD='<运维侧新生成的强口令>'      # 必需，见上
#   另需 root 连接，二选一：
#     export MYSQL_ROOT_PASSWORD='<root 口令>'               # 方式 A
#     export MYSQL_ROOT_DEFAULTS_FILE='<含 root [client] 段的 defaults 文件>'  # 方式 B
#   可选覆盖（默认对齐生产 fresh_app@localhost on fresh_db）：
#     DB_NAME=fresh_db  APP_DB_USER=fresh_app  APP_DB_HOST=localhost
#   bash deploy/grant-fresh-app.sh
# ============================================================================

set -euo pipefail

# ---- 可覆盖参数（默认对齐生产）----
DB_NAME="${DB_NAME:-fresh_db}"
APP_DB_USER="${APP_DB_USER:-fresh_app}"
APP_DB_HOST="${APP_DB_HOST:-localhost}"

die() { echo "$*" >&2; exit 1; }

# ---- 标识符校验（库名/账号名/主机名不可参数化，须白名单防注入）----
[[ "$DB_NAME"     =~ ^[A-Za-z0-9_]+$    ]] || die "错误：DB_NAME 只允许 [A-Za-z0-9_]，当前值非法。"
[[ "$APP_DB_USER" =~ ^[A-Za-z0-9_]+$    ]] || die "错误：APP_DB_USER 只允许 [A-Za-z0-9_]，当前值非法。"
[[ "$APP_DB_HOST" =~ ^[A-Za-z0-9_.-]+$  ]] || die "错误：APP_DB_HOST 只允许 [A-Za-z0-9_.-]，当前值非法。"

# ---- 1) 新账号口令：必须来自环境变量，否则拒绝执行 ----
if [[ -z "${FRESH_APP_PASSWORD:-}" ]]; then
  cat >&2 <<'MSG'
错误：未设置环境变量 FRESH_APP_PASSWORD（新应用账号的登录口令）。

      该口令应由运维侧随机生成、不入库、不落盘，例如：
        export FRESH_APP_PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 28)"
      或 openssl rand -base64 24 等等价的运维口令生成器。
      设置后重跑本脚本。口令全程只走 stdin，绝不写进任何文件、绝不回显。
MSG
  exit 1
fi

# ---- 2) 定位高权限(root)连接方式：MYSQL_ROOT_PASSWORD 或 MYSQL_ROOT_DEFAULTS_FILE 二选一 ----
ROOT_USER="${MYSQL_ROOT_USER:-root}"
ROOT_HOST="${MYSQL_ROOT_HOST:-}"      # 留空=用客户端默认主机（本仓既有姿势：不指定 host）
ROOT_PORT="${MYSQL_ROOT_PORT:-}"      # 留空=用客户端默认端口
root_pw=""
if [[ -n "${MYSQL_ROOT_PASSWORD:-}" ]]; then
  root_pw="${MYSQL_ROOT_PASSWORD}"
elif [[ -n "${MYSQL_ROOT_DEFAULTS_FILE:-}" ]]; then
  [[ -r "${MYSQL_ROOT_DEFAULTS_FILE}" ]] || die "错误：MYSQL_ROOT_DEFAULTS_FILE 不可读：${MYSQL_ROOT_DEFAULTS_FILE}"
  # 从既有 defaults 文件抽取 password/user 的值（只取等号右侧，绝不回显值本身）
  root_pw="$(grep -m1 -iE '^[[:space:]]*password[[:space:]]*=' "${MYSQL_ROOT_DEFAULTS_FILE}" | cut -d= -f2- | tr -d '\r')"
  root_pw="${root_pw#"${root_pw%%[![:space:]]*}"}"; root_pw="${root_pw%"${root_pw##*[![:space:]]}"}"   # 去首尾空白
  [[ -n "${root_pw}" ]] || die "错误：无法从 ${MYSQL_ROOT_DEFAULTS_FILE} 解析出 password= 项。"
  if [[ -z "${MYSQL_ROOT_USER:-}" ]]; then
    _ru="$(grep -m1 -iE '^[[:space:]]*user[[:space:]]*=' "${MYSQL_ROOT_DEFAULTS_FILE}" | cut -d= -f2- | tr -d ' \r')"
    [[ -n "${_ru}" ]] && ROOT_USER="${_ru}"
  fi
else
  cat >&2 <<'MSG'
错误：需要以 root/高权限账号执行建号授权。请二选一提供其凭据（都不进命令行）：
  (A) export MYSQL_ROOT_PASSWORD='<root 口令>'
  (B) export MYSQL_ROOT_DEFAULTS_FILE='<含 root [client] 段的 defaults 文件路径>'
MSG
  exit 1
fi

# ---- 3) 生成临时 defaults 文件（root 连接与建号都靠它）：mktemp + 600 + trap 删除 ----
TMP_CNF="$(mktemp)"
cleanup(){ rm -f "${TMP_CNF}"; }
trap cleanup EXIT INT TERM
chmod 600 "${TMP_CNF}"
# 只写 root 的 [client] 段。root 口令落此【临时】文件是本仓 defaults-extra-file 纪律的既定做法
#（mktemp 唯一路径 + 600 + trap 即删）；新账号口令 FRESH_APP_PASSWORD 绝不写进此文件。
{
  printf '[client]\n'
  printf 'user=%s\n'     "${ROOT_USER}"
  printf 'password=%s\n' "${root_pw}"
  [[ -n "${ROOT_HOST}" ]] && printf 'host=%s\n' "${ROOT_HOST}"
  [[ -n "${ROOT_PORT}" ]] && printf 'port=%s\n' "${ROOT_PORT}"
  printf 'default-character-set=utf8mb4\n'
} > "${TMP_CNF}"

mroot(){ mysql --defaults-extra-file="${TMP_CNF}" "$@"; }   # --defaults-extra-file 必须最先（见头注）

# ---- 4) 目标库存在性软校验（不存在不致命：MySQL 允许对未来库授权；但提醒运维确认）----
db_exists="$(mroot -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${DB_NAME}';" 2>/dev/null || echo 0)"
if [[ "${db_exists}" != "1" ]]; then
  echo "提示：库 '${DB_NAME}' 目前不存在；授权仍会写入（MySQL 支持对未来库授权）。若这是演练用的 scratch 库，请确认已由 root 预建。" >&2
fi

# ---- 5) 复位并授权（DROP 幂等 + CREATE 带新口令 + 仅四项 db 级权限）----
#   口令经 stdin（heredoc）流入，不进 argv；对 SQL 单引号做转义（' → ''）以兼容含引号的口令。
pw_sql="${FRESH_APP_PASSWORD//\'/\'\'}"
mroot <<SQL
-- 复位：先删（IF EXISTS 保证幂等/首跑不报错），连带清除该账号在其它库的历史授权
DROP USER IF EXISTS '${APP_DB_USER}'@'${APP_DB_HOST}';
-- 重建：仅设口令，不加任何全局权限（新账号只得到隐式 USAGE ON *.*，即「能连、零权限」）
CREATE USER '${APP_DB_USER}'@'${APP_DB_HOST}' IDENTIFIED BY '${pw_sql}';
-- 恰好四项库级权限，仅此库
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${DB_NAME}\`.* TO '${APP_DB_USER}'@'${APP_DB_HOST}';
FLUSH PRIVILEGES;
SQL

# ---- 6) 自证：打印 SHOW GRANTS（这行不含口令，可安全回显）----
echo "== 授权自证：SHOW GRANTS FOR '${APP_DB_USER}'@'${APP_DB_HOST}' =="
mroot -N -e "SHOW GRANTS FOR '${APP_DB_USER}'@'${APP_DB_HOST}';"
echo
echo "完成：账号 '${APP_DB_USER}'@'${APP_DB_HOST}' 现仅持有 ${DB_NAME}.* 的 SELECT/INSERT/UPDATE/DELETE。"
echo "临时 root defaults 文件已由 trap 删除；口令未写入任何文件、未回显。"
