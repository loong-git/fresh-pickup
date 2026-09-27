#!/bin/bash
# ============================================================================
# 生鲜数据库每日备份脚本（M3-T-10 部署产出物，未实际部署）
# 功能：mysqldump 导出 fresh_db → gzip 压缩 → 按日期存 /data/fresh/backup/ → 只保留最近 7 份
#
# 手动执行：
#   chmod +x backup.sh && ./backup.sh
#
# 每日定时（crontab -e 追加，每天凌晨 2 点执行）：
#   0 2 * * * /data/fresh/deploy/backup.sh >> /data/fresh/backup/backup.log 2>&1
#
# 安全说明：密码默认从环境变量 MYSQL_PASS 读取（生产用 root 密码或为 fresh_backup
# 专用最小权限账号赋 SELECT 权限），脚本内的默认值仅适配当前开发环境。
# ============================================================================
set -euo pipefail

# ---- 可覆盖配置：生产环境用环境变量注入，避免明文密码入库到代码仓 ----
BACKUP_DIR="${BACKUP_DIR:-/data/fresh/backup}"   # 备份输出目录
DB_NAME="${DB_NAME:-fresh_db}"                   # 库名
DB_USER="${MYSQL_USER:-root}"                    # 账号
DB_PASS="${MYSQL_PASS:-change-me}"             # 密码（生产必须用环境变量覆盖）
KEEP=7                                           # 保留最近 7 份

# 通过 MYSQL_PWD 环境变量传密码，避免命令行密码在 ps/日志中泄露，也消除 mysqldump 告警
export MYSQL_PWD="$DB_PASS"

mkdir -p "$BACKUP_DIR"

# 文件名按日期：fresh_db_2026-09-27.sql.gz（同日重复执行覆盖同名文件）
STAMP="$(date +%F)"
OUT_FILE="$BACKUP_DIR/${DB_NAME}_${STAMP}.sql.gz"

echo "[$(date '+%F %T')] 开始备份 $DB_NAME → $OUT_FILE"

# --single-transaction：InnoDB 一致性快照，不锁表
# --routines --triggers：连同存储过程/触发器一起备份
if mysqldump -u"$DB_USER" --single-transaction --routines --triggers "$DB_NAME" | gzip > "$OUT_FILE"; then
    # 空文件视为失败（mysqldump 部分异常场景退出码可能仍为 0）
    if [ ! -s "$OUT_FILE" ]; then
        echo "[$(date '+%F %T')] 备份失败：输出文件为空 $OUT_FILE"
        rm -f "$OUT_FILE"
        exit 1
    fi
    echo "[$(date '+%F %T')] 备份完成：$(du -h "$OUT_FILE" | awk '{print $1}')"
else
    echo "[$(date '+%F %T')] 备份失败：mysqldump 退出码非 0"
    rm -f "$OUT_FILE"
    exit 1
fi

# ---- 清理旧备份：按修改时间倒序，只保留最近 KEEP 份 ----
ls -1t "$BACKUP_DIR"/${DB_NAME}_*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
    echo "[$(date '+%F %T')] 清理过期备份：$old"
    rm -f "$old"
done

echo "[$(date '+%F %T')] 备份任务结束"
