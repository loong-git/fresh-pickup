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
 * 运营端 v2 只读面（F-20/G-06 Step 3，D-7；F-20.18 Step 5b-1 扩到六条）：{@code /api/admin/v2} 下六条
 * <b>GET 映射</b>——大盘（四指标 + F-50 的当日回调拒收两键）+ 审计日志分页 + 四条运营列表（orders / dishes / reviews / store-applies）。
 * v2 <b>至今不挂任何写映射</b>（POST/PUT/DELETE 三类一个都不出现，验收断言「写映射 0、GET 映射恰 6」，
 * 与本文件的注解计数逐字相等）：
 * 订单管理/商品管理/评价审核/入驻审核四个 P0 页的<b>写</b>动作仍走 {@link AdminController} 的旧 5 端点
 * （D-7 明令不在 v2 重复造写接口），本批给它们的是<b>列表数据源</b>，两者不重叠。
 *
 * <p><b>鉴权只认运营 JWT</b>：六条端点<b>方法体</b>第一行调 {@link AdminController#requireAdminJwtOnly}（F-20.6 第 6 条备好的那把闸门，
 * 本期首次派活；闸门方法留在 AdminController 里<b>只有一份</b>，不在此复制三重判定）。
 * <p><b>如实声明判定时序</b>（F-20.27 A-2 → 登记 L-20-2，本期不改判定顺序）：Spring 的<b>参数绑定与类型转换</b>
 * （{@code @RequestParam}/{@code @RequestHeader} 解析）发生在方法体执行<b>之前</b>，即闸门调用之前——
 * 于是匿名调用方可用 400/401 差集枚举参数名与类型（实测 {@code /orders?storeId=abc} → 400「参数格式不正确」、
 * 裸 {@code /orders} → 401；{@code /reviews?auditStatus=zzz} → 401，Service 层白名单在闸门之后、不外泄）。
 * 把判定提到绑定之前要动 {@code AuthInterceptor} 的 admin 分支（红线邻域），换到的只是「少暴露几个参数名」，
 * 得不偿失，故本期只改本注释措辞、不动代码顺序。
 * {@code X-Admin-Key} 静态密钥在 v2 上<b>一律 401</b>（D-2「防止新代码更弱」），401 文案仍是 RE 红线那一句
 * 「管理密钥缺失或错误」，逐字节未变。
 * <p>{@code AuthInterceptor} 对 {@code /api/admin/} 前缀整体提前放行（BE-20-1），v2 天然命中同一条，
 * 因此本面的服务端闸门也只有这里这一道；C 端 token 因 {@code typ<>admin} 走同一枚 401。
 *
 * <p><b>限流维度用 IP，不用 USER</b>（契约允许二选一，这里给实测理由）：
 * {@code RateLimitInterceptor#bucketKey}（{@code interceptor/RateLimitInterceptor.java} 里那个私有方法）在 USER 分支读的是
 * {@code request.getAttribute(AuthInterceptor.ATTR_USER_ID)}，取不到就退化成 {@code "user:ip:" + clientIp + ":" + api}。
 * 而 BE-20-1 起 {@code AuthInterceptor} 在 {@code /api/admin/} 前缀上<b>开头就提前 return true</b>，
 * 那两处 setAttribute(ATTR_USER_ID) 在 admin 面根本走不到——也就是说 v2 上 USER 维度<b>永远</b>是「按 IP 记账、
 * 却挂着 user: 前缀」的假象（不会 NPE，但既不是按运营账号也不是按用户，还比 IP 更容易被误读成按人限流）。
 * 想按 operator 限流就得往 admin 面注入 ATTR_USER_ID，那正是 BE-20-1 明令禁止的越权面。
 * 故六条都显式写 {@code keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60}（RF 随建随挂，
 * 新增四条同 v2 现档，D-14）。
 *
 * <p>参数一律 {@code @RequestParam} 而非 DTO（六条都是纯读、0-5 个参数，同 {@code MerchantOrderController} 的姿势）；
 * 没有任何归属入参，也不接受任何「操作者身份」入参——身份只从 header 来。
 * 脏值（非法 date / pageNum / pageSize / 白名单外的筛选值）由 Service 抛 400，复用既有文案；类型不匹配
 * （如 {@code ?storeId=abc} 绑不进 Long）交 {@code GlobalExceptionHandler:70} 转 400。
 * 出参为 Service 逐键重拼的手工白名单 Map（含脱敏与「不查询」两层），HTTP 恒 200、业务码只看 body.code。
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
     * → {@code {orderCount, totalAmount, pendingPickupCount, paidAmount, notifyRejectCount, notifyRejectLastAt}}
     * （后两键 F-50 追加：当日「微信支付回调拒收」的条数 + 最后一次拒收的 HH:mm，前四键形状一字未变）。
     * 四指标口径逐字照 {@code docs/日常运营SQL手册.md:125-132}（订单口径，与 G-05 券前明细口径分列）；
     * date 缺省 = 今日（SQL 侧 CURDATE()），非 {@code YYYY-MM-DD} 形状或假日期 → 400；
     * 金额出两位小数字符串，空数据日四个 0 值不出 null；
     * 拒收那两键同一个自然日窗口，当日没有拒收时 {@code notifyRejectLastAt} 是<b>显式 null</b>（键必在，F-42b 信封纪律）。
     * <p>本批<b>没有新增端点、没有新增限流档</b>（D-50-1）：拒收计数就挂在这条已有只读端点的 data 上，
     * 闸门与 {@code @RateLimit} 注解逐字不动。
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
     * 审计日志分页（F-20.9 #3 + F-20.16）：{@code GET /api/admin/v2/audit-logs?pageNum=&pageSize=&actor=&action=&targetType=&targetId=}
     * → {@code {list:[{id, actor, action, targetType, targetId, detail, createdAt}], total, pageNum, pageSize, filters}}。
     * 手写「页 + 独立 COUNT」（RB）；{@code ORDER BY id DESC} 稳定翻页；
     * pageNum 下限 1、pageSize 缺省 20 上限 100，越界/脏值 400；空表/无命中 {@code {list:[],total:0}} 不 500。
     * <b>{@code ip} 不下发</b>（RD 最小暴露，SQL 侧连列都没查）。只读动作不写审计（RF 只约束写动作）。
     * <p>F-20.16（收 G-06 DoD ④）四个<b>可选、精确等值</b>筛选 {@code actor}/{@code action}/{@code targetType}/{@code targetId}：
     * 一律 {@code @RequestParam(required=false)}、全可缺省（缺省 = 不筛）；形状闸门与非空校验在 Service 层
     * （脏形状 → 400，复用既有文案）。<b>闸门、限流注解、返回类型一律不动</b>，本条自身仍是 0 写映射
     * （F-20.16 当时面上恰 2 条 GET 映射，F-20.18 追加四条后共 6 条，见类注释）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/audit-logs")
    public R<Map<String, Object>> auditLogs(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize,
                                            @RequestParam(required = false) String actor,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String targetType,
                                            @RequestParam(required = false) String targetId) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.auditLogs(pageNum, pageSize, actor, action, targetType, targetId));
    }

    /**
     * 运营端订单列表（F-20.18，{@code GET /api/admin/v2/orders}，订单管理页的数据源）：
     * {@code ?pageNum=&pageSize=&status=&storeId=&date=} →
     * {@code {list:[{id,totalPrice,address,phone,pickupDate,status,createTime,updateTime,storeId,storeName,storeAddress,payStatus,payTime}], total, pageNum, pageSize, filters:{status,storeId,date}}}。
     * <p>闸门/限流/返回类型与前两条<b>逐字同一套</b>（D-14）：{@code requireAdminJwtOnly} 复用、不复制第二份三重判定；
     * 分页照 {@code auditLogs} 那一套（D-16：pageNum≥1、pageSize∈[1,100] 缺省 20、越界与脏值一律 400 不夹取、
     * {@code offset>=total} 短路回空 list）；取数是 {@code OrderMapper} 的「页 + 独立 COUNT」两条手写 SQL
     * （RB：本工程没装 MP 分页插件，那条分页通道会静默回全量且 total=0，故一律不用；
     * 两条 WHERE 共用 {@code ORDER_FILTER_WHERE} 那个编译期常量串）。
     * <p>三个筛选全<b>可选、精确等值</b>（D-17，本批零 LIKE、不做关键字搜索）：
     * {@code status} ∈ 三态白名单（白名单外 400）、{@code storeId} 等值、{@code date=YYYY-MM-DD} 按
     * {@code create_time} 自然日（与 {@code overview} 同窗口口径，缺省=不限，非法形状/假日期 400）。
     * <p><b>脱敏面（D-15）</b>：{@code phone} 出 {@code PhoneMask.mask} 的掩码值；{@code pickup_code}（取货码=核销凭证）、
     * {@code user_id}、{@code client_request_id} <b>禁下发</b>，在 SQL 层连列都没选。
     * 只读动作不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/orders")
    public R<Map<String, Object>> orders(@RequestHeader(value = "Authorization", required = false) String authz,
                                          @RequestParam(required = false) Integer pageNum,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long storeId,
                                          @RequestParam(required = false) String date) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.orders(pageNum, pageSize, status, storeId, date));
    }

    /**
     * 运营端商品列表（F-20.18，{@code GET /api/admin/v2/dishes}，商品管理台的数据源；一期<b>只改不建</b>——
     * 平台侧本就没有「新建商品」端点，见 F-20.17 事实 3 附注）：
     * {@code ?pageNum=&pageSize=&merchantId=&onSale=&category=&auditStatus=} →
     * {@code {list:[{id,name,price,stock,onSale,soldCount,category,unit,image,goodRate,seckill,seckillPrice,limitBuy,merchantId,auditStatus,auditReason,createTime,updateTime}], total, pageNum, pageSize, filters:{merchantId,onSale,category,auditStatus}}}。
     * <p>闸门/限流/分页/取数与 {@code /orders} 同一套（D-14/D-16/RB），两条 SQL 共用
     * {@code DishMapper.DISH_FILTER_WHERE}。
     * <p>四个筛选（D-17）：{@code merchantId} 等值（本面是<b>筛选</b>不是归属闸门——运营要的是全量语义，
     * 绝不许拿 {@code /api/merchant/**} 那种恒带归属谓词的接口凑数，F-20.17 事实 1）、
     * {@code onSale} ∈ {0,1}（其余值 400）、{@code category} 等值（先过形状闸门）、
     * {@code auditStatus} ∈ {ok,rejected}（F-116 §2.1-1/§2.3 审核态筛选 tab，其余值 400；null=全量）。
     * <b>没有</b>关键字搜索参数：D-17 把「商品搜索框」明确登记为边界，不许顺手加。
     * <p>{@code onSale}/{@code seckill} 出 0/1 原值由页面映射文案（D-18）；{@code price}/{@code seckillPrice}
     * 是服务端定形两位小数字符串；{@code description/tags/emoji/bg_color} 不选（RD 最小暴露）；
     * auditStatus/auditReason 出库里原值供审核态列与驳回理由 tooltip（F-116 §2.3）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/dishes")
    public R<Map<String, Object>> dishes(@RequestHeader(value = "Authorization", required = false) String authz,
                                          @RequestParam(required = false) Integer pageNum,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) Long merchantId,
                                          @RequestParam(required = false) Integer onSale,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String auditStatus) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.dishes(pageNum, pageSize, merchantId, onSale, category, auditStatus));
    }

    /**
     * 运营端评价审核队列（F-20.18，{@code GET /api/admin/v2/reviews}）：
     * {@code ?pageNum=&pageSize=&auditStatus=} →
     * {@code {list:[{id,dishId,rating,content,reviewer,auditStatus,createTime}], total, pageNum, pageSize, filters:{auditStatus}}}。
     * <p>闸门/限流/分页/取数同前（D-14/D-16/RB），两条 SQL 共用 {@code ReviewMapper.REVIEW_FILTER_WHERE}。
     * <p>{@code auditStatus} 是两枚 <b>UI 伪值</b>（D-17）：{@code pending}（待人工复审）与 {@code visible}（展示中），
     * SQL 侧映射钉死 {@code pending → audit_status='pending'}、{@code visible → audit_status IS NULL}，
     * 白名单外一律 400。<b>没有第三档</b>：{@code AdminServiceImpl#auditReview} 的 reject 是物理删行，
     * 库里不存在「已驳回」这一态（页面文案也绝不许写「已驳回列表」）。
     * <p>{@code filters.auditStatus} 回的是<b>UI 伪值本身</b>（D-19），行里的 {@code auditStatus} 是库里原值
     * （{@code 'pending'} 或 {@code null}=展示中）。{@code user_id} <b>禁下发</b>（D-15，SQL 层就没选）。
     * <p>现库 {@code review} <b>0 行</b>是实况：本条要正常回 {@code {list:[],total:0}} 而不是 500
     * （页 SQL 由 {@code offset>=total} 短路根本不执行，COUNT 对空表回 0）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/reviews")
    public R<Map<String, Object>> reviews(@RequestHeader(value = "Authorization", required = false) String authz,
                                          @RequestParam(required = false) Integer pageNum,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) String auditStatus) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.reviews(pageNum, pageSize, auditStatus));
    }

    /**
     * 运营端入驻申请列表（F-20.18，{@code GET /api/admin/v2/store-applies}，入驻审核工作台的数据源）：
     * {@code ?pageNum=&pageSize=&status=} →
     * {@code {list:[{id,proposedName,proposedAddress,lng,lat,service,contactName,contactPhone,status,auditRemark,auditedAt,createdAt}], total, pageNum, pageSize, filters:{status}}}。
     * <p>闸门/限流/分页/取数同前（D-14/D-16/RB），两条 SQL 共用 {@code StoreApplyMapper.STORE_APPLY_FILTER_WHERE}。
     * <p>{@code status} 三态白名单 {@code pending}/{@code approved}/{@code rejected}（DEFAULT pending，见 init.sql 的 store_apply），
     * 白名单外 400（D-17）。
     * <p><b>脱敏面（D-15）</b>：{@code contactPhone} 出 {@code PhoneMask.mask} 的掩码值（该列可空，null 原样回 null）；
     * {@code proof_photos} <b>不下发</b>（执照图匿名映射仍是 §9 悬项）、{@code user_id} 不下发、
     * 生成列 {@code pending_guard} <b>绝不 SELECT</b>——三者都在 SQL 层就「不查询」。
     * {@code lng/lat} 与 {@code store_address}（{@code /orders} 面）一样按契约可下发：自提点坐标不是个人信息。
     * <p>现库 {@code store_apply} <b>0 行</b>是实况，同 {@code /reviews}：回 {@code {list:[],total:0}} 而不是 500。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/store-applies")
    public R<Map<String, Object>> storeApplies(@RequestHeader(value = "Authorization", required = false) String authz,
                                               @RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize,
                                               @RequestParam(required = false) String status) {
        R<Map<String, Object>> denied = adminController.requireAdminJwtOnly(authz);
        if (denied != null) {
            return denied;
        }
        return R.ok(adminV2Service.storeApplies(pageNum, pageSize, status));
    }
}
