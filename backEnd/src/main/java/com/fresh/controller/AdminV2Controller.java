package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.service.AdminV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营端 v2 只读面（F-20/G-06 Step 3，D-7）：{@code /api/admin/v2} 下两条 <b>@GetMapping</b>，
 * 大盘四指标 + 审计日志分页。本期 v2 <b>不挂任何写映射</b>（POST/PUT/DELETE 三类一个都不出现），
 * 核销台/商品管理/评价审核/入驻审核四个 P0 页继续消费 {@link AdminController} 的旧 5 端点（D-7 明令不在 v2 重复造写接口）。
 *
 * <p><b>鉴权只认运营 JWT</b>：两条端点第一行调 {@link AdminController#requireAdminJwtOnly}（F-20.6 第 6 条备好的那把闸门，
 * 本期首次派活；闸门方法留在 AdminController 里<b>只有一份</b>，不在此复制三重判定）。
 * {@code X-Admin-Key} 静态密钥在 v2 上<b>一律 401</b>（D-2「防止新代码更弱」），401 文案仍是 RE 红线那一句
 * 「管理密钥缺失或错误」，逐字节未变。
 * <p>{@code AuthInterceptor} 对 {@code /api/admin/} 前缀整体提前放行（BE-20-1），v2 天然命中同一条，
 * 因此本面的服务端闸门也只有这里这一道；C 端 token 因 {@code typ<>admin} 走同一枚 401。
 *
 * <p><b>限流维度用 IP，不用 USER</b>（契约允许二选一，这里给实测理由）：
 * {@code RateLimitInterceptor#bucketKey}（{@code interceptor/RateLimitInterceptor.java:79-86}）在 USER 分支读的是
 * {@code request.getAttribute(AuthInterceptor.ATTR_USER_ID)}，取不到就退化成 {@code "user:ip:" + clientIp + ":" + api}。
 * 而 BE-20-1 起 {@code AuthInterceptor:73-75} 对 {@code /api/admin/} 前缀<b>提前 return true</b>，
 * 那两处 setAttribute（:89/:98）在 admin 面根本走不到——也就是说 v2 上 USER 维度<b>永远</b>是「按 IP 记账、
 * 却挂着 user: 前缀」的假象（不会 NPE，但既不是按运营账号也不是按用户，还比 IP 更容易被误读成按人限流）。
 * 想按 operator 限流就得往 admin 面注入 ATTR_USER_ID，那正是 BE-20-1 明令禁止的越权面。
 * 故两条都显式写 {@code keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60}（RF 随建随挂）。
 *
 * <p>参数一律 {@code @RequestParam} 而非 DTO（两个都是纯读、0-2 个参数，同 {@code MerchantOrderController} 的姿势）；
 * 没有任何归属入参，也不接受任何「操作者身份」入参——身份只从 header 来。
 * 脏值（非法 date / pageNum / pageSize）由 Service 抛 400，复用既有文案；类型不匹配交
 * {@code GlobalExceptionHandler} 转 400。出参为 Service 逐键重拼的手工白名单 Map，HTTP 恒 200、业务码只看 body.code。
 */
@RestController
@RequestMapping("/api/admin/v2")
public class AdminV2Controller {

    @Autowired
    private AdminV2Service adminV2Service;

    /**
     * v2 闸门复用（包内可见方法，见 {@link AdminController#requireAdminJwtOnly} 的可见性说明）：
     * 注 Controller 而非新建 guard 组件，是本期允许文件清单内的唯一「不复制闸门」的接法。
     */
    @Autowired
    private AdminController adminController;

    /**
     * 只读大盘（F-20.9 #2）：{@code GET /api/admin/v2/overview?date=YYYY-MM-DD}
     * → {@code {orderCount, totalAmount, pendingPickupCount, paidAmount}}。
     * 四指标口径逐字照 {@code docs/日常运营SQL手册.md:111-115}（订单口径，与 G-05 券前明细口径分列）；
     * date 缺省 = 今日（SQL 侧 CURDATE()），非 {@code YYYY-MM-DD} 形状或假日期 → 400；
     * 金额出两位小数字符串，空数据日四个 0 值不出 null。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestHeader(value = "Authorization", required = false) String authz,
                                           @RequestParam(required = false) String date) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.overview(date));
    }

    /**
     * 审计日志分页（F-20.9 #3）：{@code GET /api/admin/v2/audit-logs?pageNum=&pageSize=}
     * → {@code {list:[{id, actor, action, targetType, targetId, detail, createdAt}], total, pageNum, pageSize}}。
     * 手写「页 + 独立 COUNT」（RB）；{@code ORDER BY id DESC} 稳定翻页；
     * pageNum 下限 1、pageSize 缺省 20 上限 100，越界/脏值 400；空表 {@code {list:[],total:0}} 不 500。
     * <b>{@code ip} 不下发</b>（RD 最小暴露，SQL 侧连列都没查）。只读动作不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/audit-logs")
    public R<Map<String, Object>> auditLogs(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.auditLogs(pageNum, pageSize));
    }
}
