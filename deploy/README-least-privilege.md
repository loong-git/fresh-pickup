# F-43 | 应用运行账号最小权限（`fresh_app`）

把 T-M1-11 验收②「应用改用最小权限 MySQL 账号」从 `application-prod.yml` 的一句注释，
落成可执行、可验证的东西：授权脚本 + 权限边界断言。

## 权限模型（恰好四项，仅此库）

`'fresh_app'@'localhost'` 仅持有 `fresh_db.*` 的：`SELECT, INSERT, UPDATE, DELETE`。
其余全部不给：**无 DDL**（`CREATE/ALTER/DROP/INDEX/TRUNCATE`）、无 `GRANT OPTION`、无 `TRIGGER`、无全局权限（除隐式 `USAGE ON *.*`，即「能连、零权限」）。

- **为什么 DELETE 必须有**：运营端驳回评价是**物理删除**——
  `backEnd/src/main/java/com/fresh/service/impl/AdminServiceImpl.java:226` 的
  `reviewMapper.deleteById(reviewId)`（同方法 `:212` selectById、`:220` update 为过审分支），
  MyBatis-Plus 的 `deleteById` 会生成 `DELETE FROM review …`。因此 `application-prod.yml:1`
  那句「仅 SELECT/INSERT/UPDATE/DELETE」是对的；「无 DELETE」是过时的验收表述。
- **为什么不给 DDL**：全仓运行时无任何建表/改表语句（运行时代码里无 `ALTER/CREATE/DROP TABLE`
  字面量），建库与迁移全部由**人工**执行 `backEnd/sql/*.sql`（`init.sql` + `m3~m19-migration.sql`）。
  应用账号无需、也不应拿到 DDL。

## 怎么跑

`deploy/grant-fresh-app.sh` 用 `DROP USER IF EXISTS → CREATE USER → GRANT` 实现**幂等复位**
（每次产出逐字节相同的终态，首跑也不报错；DROP 会顺带清掉该账号在其它库上的历史残留授权，
严格回到最小权限）。口令一律走环境变量 + `--defaults-extra-file`，绝不进命令行/文件/回显。

```bash
# 新账号口令：运维侧随机生成，不入库（本机实测 MySQL 8.0.36 客户端要求
#   --defaults-extra-file 必须是最先选项，不能与 --no-defaults 同用，故脚本只用后者单独形式）
export FRESH_APP_PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 28)"

# 高权限(root)连接，二选一：
export MYSQL_ROOT_PASSWORD='<root 口令>'                        # 方式 A
# 或 export MYSQL_ROOT_DEFAULTS_FILE='<含 root [client] 段的 defaults 文件>'   # 方式 B

bash deploy/grant-fresh-app.sh          # 默认 DB_NAME=fresh_db APP_DB_USER=fresh_app APP_DB_HOST=localhost
```

跑完会打印 `SHOW GRANTS FOR 'fresh_app'@'localhost'` 作为自证（该行不含口令）。

## `FRESH_APP_PASSWORD` 从哪来

由**运维侧**在部署环境随机生成（如上面的 `tr`/`openssl rand`），注入到运行环境，
**不进版本库、不落文件**。脚本仅从该环境变量读取，全程只经 stdin 流入 SQL。

## 上线时 prod 侧还需要什么

- `application-prod.yml` **已是** `username: fresh_app` + `password: ${DB_PASSWORD}`（键值请勿改动）；
  部署时把 `DB_PASSWORD` 设成与本脚本写入的 `FRESH_APP_PASSWORD` **同一个口令**即可（两处一致，
  由运维在 `deploy/fresh.service` 的 `Environment=`/`EnvironmentFile`（权限 600）注入，不写进随仓库公开的 unit 文件）。
- 先由 root/DBA 跑 `backEnd/sql/*.sql` 建好 `fresh_db` 及迁移，再跑本脚本授权。
- 主机限定 `localhost`：本仓应用与 MySQL 同机（`deploy/fresh.service` 亦然，`After=mysql.service`）。

## 验证

- `deploy/grant-verify.sql`：用**应用账号身份**逐条断言权限矩阵（4 条应成功全 PASS、
  6 条应被拒全 `REJECTED_AS_EXPECTED`，拒绝错误码 1044/1142）。凭据同样只走 `--defaults-extra-file`。
- 本批已在 **scratch 库 `scratch_minpriv`** 上真跑一遍并逐条留证；未对真 `fresh_db` 做任何写操作或 DDL。
