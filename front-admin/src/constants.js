// F-54 改动 B｜审计列值的前端单一出口
//
// 这两枚值不是前端发明的字符串，而是**服务端写死的审计列值**，前端只是复述：
//   - actor 值出自 AdminServiceImpl 的 NOTIFY_REJECT_ACTOR（微信回调被拒那一刻由服务端自己写进行里，
//     没有任何人在场操作，所以 actor 恒为这一个系统身份）；
//   - action 值出自 AdminAuditLogMapper#ACTION_PAY_NOTIFY_REJECT（写侧与大盘读侧「当日拒收计数」同源，
//     服务端本来就只有一枚出口）。
// 因此这两枚值的真值在服务端，本文件只是它在运营端的镜像。**服务端改名必须两端同步**：
// 改完服务端再改这里一处，全站（审计页预设 + 大盘跳转）同时生效。
// 引用出处一律写符号名、不写行号——本仓 F-27/F-41 定案：具名后挂行号是二次漂移面。
//
// 为什么要收成单一出口（改动前的事实）：两枚值在运营端共四处代码——
// actor 两处（AuditLogs.vue 的 QUICK_FILTERS 上方那枚本地常量定义 + Overview.vue 的 goNotifyRejectAudit 内联），
// action 两处（那两页各内联一遍）。四处就是四份真值，将来服务端改名必有一处静默失配——
// 失配的那边不会报错，只会筛出 0 行，运营读到的是「今天没有拒收」。
// 现在四处都指向本文件，本文件是运营端唯一允许内联这两枚串的地方（判据只看非注释代码行）。
export const AUDIT_ACTOR_SYSTEM_WX_NOTIFY = 'system:wx-notify'

// 审计表 admin_audit_log.action 列的取值（VARCHAR(50)，服务端定值 17 字符）
export const AUDIT_ACTION_PAY_NOTIFY_REJECT = 'pay.notify_reject'
