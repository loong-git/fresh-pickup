#!/usr/bin/env bash
# ============================================================================
# F-43 | 应用账号「最小权限」权限边界断言脚本
#
# 目的：用【应用账号身份】（非 root）逐条断言权限模型——该能干的都能干、
#       不该干的一律被拒（错误码 1044/1142 权限拒绝）。
#
# 运行方式（凭据只走 defaults-extra-file，绝不进命令行；实测本机 MySQL 8.0.36
#   的 --defaults-extra-file 必须是最先选项，不能与 --no-defaults 同用）：
#     export VERIFY_DEFAULTS_FILE='<指向应用账号 [client] 段的临时 defaults 文件>'
#     DB_NAME=<目标库；演练用 scratch_minpriv>  bash deploy/grant-verify.sql
#
# 说明：文件名 .sql，但按派单要求写成「mysql -e "..." && echo PASS_.. || echo REJECTED_AS_EXPECTED」
#       的逐条可执行断言形式，故由 bash 运行；每块前用 `-- 期望:` 标注预期。
#       断言对象为【scratch 库】中由 root 预建的种子表，绝不在真 fresh_db 上跑删改。
#       种子表 seed_probe(id, tag, val)：由运维/root 预建（应用账号只 CRUD、不建表）。
# ============================================================================

set -uo pipefail

: "${VERIFY_DEFAULTS_FILE:?必须提供 VERIFY_DEFAULTS_FILE（指向应用账号 [client] 段的 defaults 文件；口令不在命令行）}"
DB_NAME="${DB_NAME:-fresh_db}"
SEED="${DB_NAME}.seed_probe"
PROBE_TAG="leastpriv_probe_$$_$(date +%s)"        # 可识别的探针行标记，便于插入/更新/删除

# --defaults-extra-file 必须最先（实测本机 8.0.36 与 --no-defaults 同用会报 unknown variable）
mverify(){ mysql --defaults-extra-file="${VERIFY_DEFAULTS_FILE}" -N "$@"; }

# 应成功类：执行失败即判 FAIL（供上层以退出码/文案识别）
expect_ok(){  # $1=标签 $2=SQL
  if mverify -e "$2" >/dev/null 2>&1; then
    echo "PASS_$1"
  else
    echo "FAIL_$1（期望成功，却报错）"; return 1
  fi
}

# 应被拒类：必须因权限被拒（ERROR 1044 或 1142）才算 REJECTED_AS_EXPECTED
expect_denied(){  # $1=标签 $2=SQL
  local out rc
  out="$(mverify -e "$2" 2>&1)"; rc=$?
  if [[ $rc -ne 0 && "$out" =~ ERROR\ 1(044|142) ]]; then
    echo "REJECTED_AS_EXPECTED_$1"
  elif [[ $rc -ne 0 ]]; then
    echo "REJECTED_$1_但非预期错误码 :: ${out}"; return 1
  else
    echo "UNEXPECTED_$1（本应被权限拒绝，却执行成功）"; return 1
  fi
}

echo "===== 应用账号身份权限矩阵断言（目标库=${DB_NAME}，种子表=${SEED}）====="

# ---------- 应成功：SELECT / INSERT / UPDATE / DELETE ----------
# -- 期望: SELECT 任一种子表 成功（有 SELECT 权）
expect_ok   "SELECT"  "SELECT COUNT(*) FROM ${SEED};"
# -- 期望: INSERT 一条可识别探针行 成功（有 INSERT 权）
expect_ok   "INSERT"  "INSERT INTO ${SEED}(tag,val) VALUES('${PROBE_TAG}',-99);"
# -- 期望: UPDATE 该探针行 成功（有 UPDATE 权）
expect_ok   "UPDATE"  "UPDATE ${SEED} SET val=-98 WHERE tag='${PROBE_TAG}';"
# -- 期望: DELETE 该探针行 成功（有 DELETE 权——运营驳回评价物理删除的运行必需，见 README）
expect_ok   "DELETE"  "DELETE FROM ${SEED} WHERE tag='${PROBE_TAG}';"

# ---------- 应被拒：DDL / TRUNCATE / GRANT（一律 1044/1142）----------
# -- 期望: CREATE TABLE 被拒（无 CREATE 权）——迁移由人工跑 backEnd/sql/，应用账号不应建表
expect_denied "CREATE_TABLE" "CREATE TABLE ${DB_NAME}.t_denied_probe(id INT);"
# -- 期望: ALTER TABLE ADD COLUMN 被拒（无 ALTER 权）
expect_denied "ALTER_TABLE"  "ALTER TABLE ${SEED} ADD COLUMN col_denied INT;"
# -- 期望: DROP TABLE 被拒（无 DROP 权）；针对既有种子表，先命中权限检查→1142
expect_denied "DROP_TABLE"   "DROP TABLE ${SEED};"
# -- 期望: CREATE INDEX 被拒（无 INDEX 权）
expect_denied "CREATE_INDEX" "CREATE INDEX idx_denied ON ${SEED}(val);"
# -- 期望: TRUNCATE 被拒（TRUNCATE 需 DROP 权）
expect_denied "TRUNCATE"     "TRUNCATE TABLE ${SEED};"
# -- 期望: GRANT 被拒（无 GRANT OPTION / 无任何转授权能力）
expect_denied "GRANT"        "GRANT SELECT ON ${DB_NAME}.* TO 'probe_grantee'@'localhost';"

echo "===== 断言结束：4 条应成功全为 PASS_*，6 条应被拒全为 REJECTED_AS_EXPECTED_* ====="
