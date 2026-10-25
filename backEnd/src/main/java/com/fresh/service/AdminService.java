package com.fresh.service;

import com.fresh.dto.AdminAliasCreateDTO;
import com.fresh.dto.AdminAliasUpdateDTO;
import com.fresh.dto.AdminArrivalDepartDTO;
import com.fresh.dto.AdminCouponTemplateCreateDTO;
import com.fresh.dto.AdminCouponTemplateUpdateDTO;
import com.fresh.dto.AdminDictGroupUpdateDTO;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
import com.fresh.dto.AdminDishAuditDTO;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminFreeActivityUpdateDTO;
import com.fresh.dto.AdminFreePoolUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;
import com.fresh.dto.AdminSeckillUpdateDTO;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.dto.AdminStoreUpdateDTO;

import java.util.List;
import java.util.Map;

/**
 * 管理端薄版服务（T-M3-09）：鉴权（F-20 Step 2 起为双轨）在 Controller 层完成，此处只做业务。
 * F-12/G-01 M-04 补课：本类全部写操作随做随写 admin_audit_log（R6）。
 *
 * <p><b>F-20.9 #1（收 R-c「审计 actor 失真」）：五个写方法各追加一个 {@code String actor} 形参</b>，
 * 由 {@code AdminController} 依据双轨闸门本次走的是哪一条腿来决定传哪一枚：
 * <ul>
 *   <li>静态密钥命中 → 传 {@code null}，Service 侧沿用既有口径落 {@code admin:<key 摘要 8>}，
 *       <b>与 Step 3 之前逐字节相同</b>（旧 curl 脚本 / SQL 手册零感知，本仓也不为此再复制一份 SHA-256 实现——
 *       摘要算法只有 {@code AdminServiceImpl#adminKeyDigest} 一处真源）；</li>
 *   <li>运营 JWT 命中 → 传 {@code admin_user:<id>}（id 来自闸门查库确认过 status=active 的那枚 subject）。</li>
 * </ul>
 * actor 一律是「摘要或 id」，<b>绝不</b>是密钥明文、口令、token（RD 红线：不入代码/注释/日志/异常消息/detail）。
 * <p>本参数<b>不是</b>从 ThreadLocal 或 request 属性里偷偷取的：BE-20-1 起 AuthInterceptor 对
 * {@code /api/admin/} 前缀提前放行且<b>不注入</b>任何用户属性，那条隐式通道在本面根本不存在，
 * 取值只有「Controller 显式传」这一条路（传 null 即静态密钥轨，语义见上）。
 */
public interface AdminService {

    /**
     * 订单状态更新：仅允许 pending_pickup → completed（核销）/ cancelled（取消，回补库存）
     *
     * @param reason 取消理由（F-61）：{@code status=cancelled} 时必填、空白即 400；
     *               {@code completed} 时可空，空则审计 detail 不落这一键（不写空串冒充"填了但没内容"）
     * @param actor  本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateOrderStatus(String orderId, String status, String reason, String actor);

    /**
     * 商品部分更新：price / stock / onSale 仅更新传入字段
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateDish(Long dishId, AdminDishUpdateDTO dto, String actor);

    /**
     * 商品内容审核（F-116 §2.2，「先发后审」唯一写入口）：只动 dish.audit_status / audit_reason 两列，
     * <b>不改 on_sale</b>（两列正交，上下架仍归商家端/改价端点）。
     * <ul>
     *   <li>reject：reason 必填、trim 后 1~200 字符；置 {@code audit_status='rejected'} + 理由
     *       （驳回即 C 端不可见不可买——列表/搜索/秒杀/推荐读路径与下单拦截即刻联动）；</li>
     *   <li>restore：置 {@code 'ok'} 并清理由（body 带 reason 忽略）；</li>
     *   <li>action 非 reject/restore → 400「action 仅允许 reject/restore」；dish 不存在 → 404「菜品不存在」；</li>
     *   <li>已是目标态 → 幂等 200：不产生假变化（重复 reject 不覆盖既有理由），审计<b>照记</b>——
     *       与 {@code updateOrderStatus} 幂等早退不留痕的口径刻意不同：这里留痕的是「运营再次确认同一态」这一动作。</li>
     * </ul>
     * 成功后写审计 {@code dish.audit}（before/after 记 {auditStatus, auditReason} + action）。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditDish(Long dishId, AdminDishAuditDTO dto, String actor);

    /**
     * 评价审核（T-M4-05 收口）：命中软词入库 pending 默认不展示，此处人工放行/驳回。
     * approve → audit_status 置 NULL 恢复展示；reject → 删除评价记录。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditReview(Long reviewId, String action, String actor);

    /**
     * 平台代开通商家（F-12/M-05 一期，决策点②）：按 phone 查已注册 user，同事务
     * INSERT merchant + INSERT merchant_profile(approved) + UPDATE user role/merchant_id，并写审计。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void createMerchant(AdminMerchantCreateDTO dto, String actor);

    /**
     * 入驻申请审核（F-18.12 第 3 条，Step 4b）：action 白名单 approve/reject（复用本类 ALLOWED_AUDIT_ACTIONS）。
     * <ul>
     *   <li>approve：同事务三写——条件 UPDATE store_apply 置 approved（+audited_at）→ INSERT store
     *       （owner_user_id=申请人，这是门店进 {@code GET /api/stores} 的唯一发布通道，D-12/A2.1）→ admin_audit_log；</li>
     *   <li>reject：只置 rejected + audit_remark（申请主体保留，申请人可改后重新提交=新行，D-21）→ admin_audit_log；</li>
     *   <li>幂等：已是目标态再审 → 直接成功，<b>不重复 INSERT store</b>、不写第二行审计；</li>
     *   <li>申请不存在 → 404；已审完再翻转结论 → 400。</li>
     * </ul>
     *
     * @param remark 审核意见（可空，落 audit_remark VARCHAR(255)；超长 400）
     * @param actor  本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditStoreApply(Long applyId, String action, String remark, String actor);

    /**
     * 商家自助入驻申请审核（F-106 片 A）：action 白名单 approve/reject（复用
     * {@code AdminServiceImpl} 的 ALLOWED_AUDIT_ACTIONS，不复制第二份集合）。
     *
     * <p><b>approve 同事务五步，顺序不可调换</b>：① 查申请/幂等 → ② INSERT merchant
     * （{@code contact_phone} 取申请人 {@code user.phone}，<b>不取申请表单</b>）→ ③ INSERT
     * merchant_profile(audit_status='approved') → ④ 条件 UPDATE user
     * （{@code SET role='merchant', merchant_id=? WHERE id=? AND merchant_id IS NULL}，
     * 影响行数≠1 抛异常回滚）→ ⑤ 置申请态 + admin_audit_log。
     *
     * <p><b>reject</b>：remark 必填（空则 400），只置 {@code status='rejected'} + 审计，
     * <b>不删除</b>申请行（留档；pending_guard 变 NULL 后该用户可再次申请）。
     *
     * <p><b>幂等</b>：已 approved/rejected 再审直接成功，不重复 INSERT merchant、不写第二行审计。
     * 申请不存在 → 404；action 不在白名单 → 400。
     *
     * @param remark 审核意见（approve 可空；reject 必填；落 audit_remark VARCHAR(255)，超长 400）
     * @param actor  本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditMerchantApply(Long applyId, String action, String remark, String actor);

    /**
     * 运营端商家入驻申请列表（F-106 片 B2，{@code GET /api/admin/merchant-applies}）：
     * {@code ?status=&pageNum=&pageSize=} → {@code {list:[...], total, pageNum, pageSize, filters:{status}}}，
     * 分页出参键名与 {@code AdminV2ServiceImpl#storeApplies} 逐字一致（不新造形状）。
     *
     * <p><b>参数闸门</b>：{@code status} 可空（null 或空白 = 全部），非空仅允许
     * {@code pending|approved|rejected}（大小写不敏感，照 v2 列表族先归一小写再过白名单；
     * 白名单外 400，复用 {@code AdminV2ServiceImpl} 同一句 400 文案）；
     * {@code pageNum} 缺省 1、下限 1；{@code pageSize} 缺省 20、值域 [1,100]；越界与脏值一律 400、<b>不夹取</b>。
     *
     * <p><b>排序</b>：{@code pending} 优先（运营先看要处理的），其次 {@code created_at DESC, id DESC}。
     *
     * <p><b>脱敏</b>：{@code applicantPhone} 取自申请人 {@code user.phone}（申请行没有电话列），
     * 出参前必须过 {@link com.fresh.common.PhoneMask#mask}；明文不出本方法。
     * 申请人行被清（LEFT JOIN 不到）时该键为 null，但行仍返回。
     *
     * @param pageNum 页码，缺省 1
     * @param pageSize 每页条数，缺省 20
     * @param status  三态筛选，空 = 全部
     */
    Map<String, Object> listMerchantApplies(Integer pageNum, Integer pageSize, String status);

    /**
     * 微信支付回调拒收留痕（F-44）：把「哪一单、因为哪一条原因、回了微信什么码」落进
     * {@code admin_audit_log}，让运营在 front-admin 审计页查得到（此前只有服务器日志）。
     *
     * <p><b>为什么落在 admin_audit_log 而不是 ops_log</b>：init.sql 里 {@code ops_log} 的表注释明写
     * 「本表只收商家/主理人/申请人现场动作——平台侧动作一律走 admin_audit_log」。回调裁定是平台侧事件，
     * 按本仓既有边界就归本表，<b>不新建表、不加迁移</b>。
     *
     * <p><b>唯一调用点</b>：{@code PayController#wxNotify} 的那一处
     * {@code catch (WxPayNotifyRejectException)}——判定层 18 条 {@code reject(...)} 抛出点一条都不许自己写审计
     * （两处写 = 两套口径）。成功结算路径<b>不调本方法</b>，否则审计表被正常流量灌满、功能立刻失去可用性。
     *
     * <p><b>本方法是旁路</b>：写失败绝不影响调用方对微信的应答（那枚 HTTP 状态码是「已扣款订单不丢单」的
     * 唯一保障）。落库动作复用本面既有那唯一一份审计写入核心 {@code AdminServiceImpl#writeAudit}，
     * 不开第二个 writer。
     *
     * @param code       稳定 ASCII 拒收码（{@code WxPayNotifyRejectException.RejectCode#name()}，
     *                   与拒收原因一一对应；null/空一律归一成 {@code UNSPECIFIED}，不留空串）
     * @param httpStatus 实际回给微信的 HTTP 状态码（只作留痕，不参与任何判定）
     * @param orderId    路径上的订单号，落 {@code target_id}；null/空白落占位符 {@code '-'}
     *                   （该列 {@code VARCHAR(64) NOT NULL}，空串行插不进去）；
     *                   超过列宽则截到 64 字符——「审计行必须存在」优先于「审计行有内容」（BE-2 既有裁定）
     * @param ip         来源 IP，口径与限流闸门一致（{@code rate-limit.trust-proxy}），null 即列可空
     */
    void recordNotifyReject(String code, int httpStatus, String orderId, String ip);

    /**
     * 发车只读预览（F-63）：按店 + 批次日期回「已有批次态 + 应发汇总」，只读动作不写审计（RF 只约束写动作）。
     *
     * <p>出参形状钉死在契约：{@code storeId / storeName / date / existing / plan / orderCount / dishKinds /
     * departable}，其中 existing 为 null 或 {@code {id, status, confirmTime, itemCount}}、plan 行为
     * {@code {dishId, dishName, qty}}；departable = 无批次或既有批次 status 落在 planned/arriving 白名单，
     * （与发车判据同一套集合，前端据此禁用按钮）。
     * date 非法形状 400（复用既有文案）；门店不存在 404「门店不存在」——storeName 取自门店行，
     * 查无门店时这个出参形状根本填不出来，404 是唯一自洽出口。
     *
     * <p>预览对过去日期<b>不报 400</b>，而是把 departable 置 false（日期这一维实测在实现里生效；F-89 D1 订正：
     * 本句原写「公式契约不含日期项」抄的是旧契约口径，代码后来补了这一维）。硬 400 只在发车侧（判据顺序第 ② 步）。
     */
    Map<String, Object> arrivalPreview(Long storeId, String date);

    /**
     * 运营端「发车」（F-63）：平台统一配货模型下 {@code arrival_batch} 唯一的运行期创建方，发车即 arriving。
     *
     * <p><b>判据顺序钉死</b>（防 404 与 400 抢先后）：① DTO 的 {@code @Valid} 形状 →
     * ② batchDate 形状非法或早于今天 → 400（<b>在一切查库之前</b>，单测用
     * {@code verify(storeMapper, never())} 钉住）→ ③ 查 store，查无 404「门店不存在」→
     * ④ 已有批次 status 落在 confirmed/short → 400 拒（货已收，再发车是造假；库与审计都不动）→
     * ⑤ 按菜汇总为空 → 400「该店该日没有可发车的订单」（不建空批次）→
     * ⑥ 写批次 + 明细 + 审计。整体 {@code @Transactional}，任一步抛异常整体回滚。
     *
     * <p><b>决策点钉死项</b>：不提供 planned 入口（发车直达 arriving）；expected_qty 由系统按订单明细
     * 汇总、不收任何数字入参；已有 planned/arriving 批次走<b>幂等重算明细</b>（先删后批量插，
     * 撞 uk_store_date 的并发只可能是「查无之后被人抢插」，按 400「该店该日已有批次，请刷新后重试」处理）。
     *
     * <p><b>汇总口径</b>：{@code OrderItemMapper#selectArrivalPlanByStore} 与商家备货单的
     * {@code selectPickupPlanByStore} 逐字同口径（只差去掉 merchant_id 过滤；未支付单计入应发数），
     * 两边每道菜数字不一致就是事故。店级单数另走去重孪生条，不按 dish 分组求和冒充。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    Map<String, Object> departArrival(AdminArrivalDepartDTO dto, String actor);

    // ==================================================================
    // F-108 §0.7 片 2：字典管理端 CRUD + 引用保护（六个端点，全部双轨闸门在 Controller）
    // 写操作成功后统一 evict 该组 Caffeine 缓存，并写 admin_audit_log：
    //   dict.item.create / dict.item.update / dict.item.delete / dict.group.update
    // actor 口径与本接口既有方法一致（Controller 传 grant.actor()；null = 静态密钥轨，
    // Service 侧归一成 admin:<key 摘要 8>）。本组方法均<b>不加 @Transactional</b>：
    // 每个写操作是单条 DML，先提交再 evict，cache-aside 顺序正确；审计是旁路，写失败不打断主流程。
    // ==================================================================

    /**
     * 字典分组列表：{@code [{code,name,enabled,itemCount,sort}]}，按 {@code sort ASC, code ASC}。
     * 无词条的组 {@code itemCount=0}。
     */
    List<Map<String, Object>> dictGroups();

    /**
     * 某分组全部词条（不分页，最多几十条）：{@code [{id,word,definition,sortOrder}]}，
     * 顺序与 C 端读路径同口径（{@code sort_order DESC, id ASC}）。
     *
     * @throws com.fresh.common.BizException 分组不存在（400「字典分组不存在」）
     */
    List<Map<String, Object>> dictItems(String group);

    /**
     * 新增词条：group 必须存在（400）；word 必填（trim 后非空）；撞 {@code uk_group_word} → 400。
     * 成功后 evict(group) + 审计 {@code dict.item.create}。
     */
    void createDictItem(String group, AdminDictItemCreateDTO dto, String actor);

    /**
     * 部分更新词条：id 不存在 404；三列全空 400；word 改后撞 {@code uk_group_word} → 400。
     * 成功后 evict(该词条所属组) + 审计 {@code dict.item.update}。
     */
    void updateDictItem(Long id, AdminDictItemUpdateDTO dto, String actor);

    /**
     * 删除词条：id 不存在 404；{@code dish_category} 组先做引用保护——
     * 取 definition 里 {@code code:} 之后的部分查 {@code SELECT COUNT(*) FROM dish WHERE category=?}，
     * 大于 0 一律 400「该分类下还有 N 个商品，删除后它们会变成无主分类」；其余组直接删。
     * 成功后 evict(该词条所属组) + 审计 {@code dict.item.delete}。
     */
    void deleteDictItem(Long id, String actor);

    /**
     * 部分更新分组：code 不存在 404；{@code name}/{@code enabled} 全空 400；enabled 仅 0/1。
     * 成功后 evict(code) + 审计 {@code dict.group.update}。停用后 {@code GET /api/dict} 按空返回。
     */
    void updateDictGroup(String code, AdminDictGroupUpdateDTO dto, String actor);

    /**
     * 从上游同步某分组词条（F-108 §0.8，B 语义「只增不删」）：组不存在 400；组 {@code enabled=0}
     * 400「分组已停用，不同步」；上游拉取失败<b>原样抛 BizException</b>（400「字典服务暂时不可用，请稍后重试」）。
     * 成功时逐条 {@code INSERT IGNORE}（{@code uk_group_word} 判重），本地没有的插入、已有的（含运营改过的）
     * 一行不动；有插入才 evict 该组。成功后写审计 {@code dict.sync}。
     *
     * @return {@code {upstreamTotal, inserted, skipped}}
     */
    Map<String, Object> syncDictGroup(String group, String actor);

    // ==================================================================
    // F-109：免费领活动管理（四个端点，只动活动配置，不碰订单/价格/库存）
    // 读端点不写审计；写端点成功后写 admin_audit_log：
    //   free.activity.update / free.pool.update（actor 口径同本接口既有方法）。
    // C 端 FreeServiceImpl 读路径无任何缓存（逐次查 free_activity/free_goods_pool/dish），
    // 故此处写后无需 evict，也不凭空新增缓存。
    // ==================================================================

    /**
     * 免费领活动 + 候选池（{@code GET /api/admin/free/activity}）→
     * {@code {id,dishId,dishName,dailyQuota,dailyClaimed,dailyDate,threshold,status,
     * pool:[{id,dishId,dishName,onSale}]}}。单活动模型取 id 最小行；无活动行 404。
     */
    Map<String, Object> freeActivity();

    /**
     * 更新免费领活动（{@code PUT /api/admin/free/activity}）：<b>只允许改 dishId/dailyQuota/threshold/status</b>，
     * 四列全空 400；{@code daily_claimed}/{@code daily_date} 不接受（DTO 无字段，运行时计数器归领取链路）。
     * dishId 须存在且 on_sale=1；dailyQuota ≥ 0（0=暂停）；threshold ≥ 0；status 仅 online/offline。
     * 成功后写审计 {@code free.activity.update}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateFreeActivity(AdminFreeActivityUpdateDTO dto, String actor);

    /**
     * 免费领候选池（{@code GET /api/admin/free/pool}）→
     * {@code [{id,dishId,dishName,onSale}]}，池行 id 升序（=插入序）。无活动行 404。
     */
    List<Map<String, Object>> freePool();

    /**
     * 全量替换候选池（{@code PUT /api/admin/free/pool}）：同事务删旧插新；
     * dishIds 非空、去重、每个 dish 存在且 on_sale=1，否则 400（空池不允许）。
     * 成功后写审计 {@code free.pool.update}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateFreePool(AdminFreePoolUpdateDTO dto, String actor);

    // ==================================================================
    // F-110 搜索别名维护（四个端点，只影响搜索命中，不碰价格/库存/订单）
    // 读端点不写审计；写端点成功后写 admin_audit_log：
    //   alias.create / alias.update / alias.delete（actor 口径同本接口既有方法）。
    // C 端搜索读路径无缓存（DishMapper 逐次精确等值查 dish_alias），故写后无需 evict。
    // ==================================================================

    /**
     * 搜索别名全量列表（{@code GET /api/admin/aliases}）→
     * {@code [{id,alias,dishId,dishName,onSale}]}，按 {@code alias ASC}。
     * 下架商品同样列出（onSale=0）——下架件持有别名是合法状态。
     */
    List<Map<String, Object>> aliases();

    /**
     * 新增搜索别名（{@code POST /api/admin/aliases}）：alias trim 后非空且 ≤50；
     * dishId 必须存在（<b>不要求在售</b>）；{@code uk_alias} 冲突 → 400「该别名已存在」。
     * 成功后写审计 {@code alias.create}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void createAlias(AdminAliasCreateDTO dto, String actor);

    /**
     * 部分更新搜索别名（{@code PUT /api/admin/aliases/{id}}）：null 字段表示本次不改；
     * 两列全空 400；alias 传空白串 400；id 不存在 404；改后撞 {@code uk_alias} → 400。
     * dishId 必须存在（不要求在售）。成功后写审计 {@code alias.update}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateAlias(Long id, AdminAliasUpdateDTO dto, String actor);

    /**
     * 删除搜索别名（{@code DELETE /api/admin/aliases/{id}}）：id 不存在 404。
     * 成功后写审计 {@code alias.delete}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void deleteAlias(Long id, String actor);

    // ==================================================================
    // F-112 秒杀商品集管理（两个操作，只动 dish.seckill / seckill_price 两列，
    // 进秒杀=1+设价 / 调价=1+新价 / 移出=0 不回清价）。写端点成功后写 admin_audit_log：
    //   seckill.update（actor 口径同本接口既有方法）。
    // 红线（契约 §1.3）：SeckillController 与 DishServiceImpl#listSeckill 一行不改；
    // C 端读路径（三条件过滤）零改动，本段只写两列、不新增任何读路径。
    // ==================================================================

    /**
     * 全量 dish 的秒杀视角列表（{@code GET /api/admin/seckill/dishes}）→
     * {@code [{id,name,price,onSale,stock,soldCount,seckill,seckillPrice,limitBuy,merchantId}]}，
     * 按 id 升序。秒杀中/未进的筛选由前端做（dish 全量 28 行，不需要服务端 filter 参数）。
     * 下架品同样列出——预先配秒杀是合法状态（C 端 listSeckill 已过滤 on_sale=1，配了也不展示）。
     * 只读端点不写审计（RF 只约束写动作）。
     */
    List<Map<String, Object>> seckillDishes();

    /**
     * 更新某商品的秒杀参与态与秒杀价（{@code PUT /api/admin/seckill/dishes/{id}}）。
     * 校验顺序即报错优先级（契约 §1.2）：① dish 不存在 404「商品不存在」；
     * ② seckill 非 0/1 → 400；③ seckill=1 时 seckillPrice 必填、&gt;0、最多两位小数 →
     * 400「秒杀价必须为正数（最多两位小数）」；④ seckill=1 时 seckillPrice ≥
     * <b>库内</b>该 dish 现价（服务端读现价，不信任前端传参）→
     * 400「秒杀价必须低于原价 X.XX」。
     * seckill=0 时<b>不回清</b> seckill_price（保留历史值）。onSale 不做要求（下架品可预配）。
     * 成功后写审计 {@code seckill.update}（before/after 记两列旧新值）。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateSeckillDish(Long dishId, AdminSeckillUpdateDTO dto, String actor);

    // ==================================================================
    // F-113 券模板管理（三个操作：列表/上新/改配置；无 DELETE——已发过的模板删除 =
    // 历史 user_coupon.coupon_key 悬挂，停用即运营语义，引用保护固化为「不提供」）。
    // 读端点不写审计；写端点成功后写 admin_audit_log：
    //   coupon_template.create / coupon_template.update（actor 口径同本接口既有方法）。
    // C 端零改动：claim/center 读同一张表，运营改配置后 C 端下一次请求即生效（无缓存）。
    // ==================================================================

    /**
     * 券模板全量列表（{@code GET /api/admin/coupon-templates}）→
     * {@code [{id,couponKey,title,threshold,amount,validDays,limitPerUser,totalQuota,issuedCount,status}]}，
     * 按 id 升序。disabled 同样列出（停用是运营可见状态，不是隐藏态）。
     * 只读端点不写审计（RF 只约束写动作）。
     */
    List<Map<String, Object>> couponTemplates();

    /**
     * 上新券模板（{@code POST /api/admin/coupon-templates}）。校验顺序即报错优先级
     * （契约 §4 逐条）：key 形状 {@code ^[a-z0-9_]{2,50}$} → key 撞 uk_coupon_key 400「key 已存在」→
     * title ≤50 → threshold/amount &gt;0 两位小数 → amount &lt; threshold（抵扣≥门槛=负价单）→
     * validDays 1~365 → limitPerUser ≥0 → totalQuota NULL 或 ≥0 → status ∈ {active,disabled}。
     * 成功后写审计 {@code coupon_template.create}。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void createCouponTemplate(AdminCouponTemplateCreateDTO dto, String actor);

    /**
     * 部分更新券模板（{@code PUT /api/admin/coupon-templates/{id}}）：null 字段 = 本次不改，
     * 七列全空 400；id 不存在 404。校验同 create 但对<b>合并后的新值</b>判（amount&lt;threshold
     * 以合并后门槛为参照）。{@code coupon_key} 与 {@code issued_count} 不可改——body 带了忽略
     * （Controller 在响应 message 注明），Service 的落库白名单不含这两列。
     * 成功后写审计 {@code coupon_template.update}（before/after 记合并后完整快照）。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateCouponTemplate(Long id, AdminCouponTemplateUpdateDTO dto, String actor);

    // ==================================================================
    // F-114 自提点存量管理（两个操作：全量列表 / 部分更新白名单八字段；无新建无删除——
    // 门店的创建只经入驻审核 approve（auditStoreApply 的同事务 INSERT store），删除无运营语义）。
    // 读端点不写审计；写端点成功后写 admin_audit_log：store.update（actor 口径同本接口既有方法），
    // before/after 只记实际变化的键。
    // 红线（契约 §3）：C 端 StoreController/StoreServiceImpl 读路径、主理人端 PartnerStoreServiceImpl
    // 链路、OrderServiceImpl/F-111 拦截一行不改——本段只写 store 列，拦截读列自然联动。
    // ==================================================================

    /**
     * 自提点全量列表（{@code GET /api/admin/stores}）→
     * {@code [{id,name,address,service,status,openTime,closeTime,dailyCapacity,accepting,
     * ownerUserId(null=平台直营),createdAt}]}，按 id 升序；closed 同样列出（停业是运营可见状态）。
     * <b>不含 contact_phone/photos/lng/lat</b>（运营页用不上，少下发；contact_phone 是 PII 只在
     * 主理人端脱敏下发）。只读端点不写审计（RF 只约束写动作）。
     */
    List<Map<String, Object>> listStores();

    /**
     * 部分更新自提点（{@code PUT /api/admin/stores/{id}}）：白名单八字段（name/address/service/
     * status/openTime/closeTime/dailyCapacity/accepting）null=本次不改，八列全空 400；
     * id 不存在 404「门店不存在」。校验（契约 §1.2 逐条）：name trim 后 1~100、address trim 后 1~255、
     * service ≤50 可空、status ∈ {open,closed}、openTime/closeTime 匹配 {@code ^([01]\d|2[0-3]):[0-5]\d$}
     * 或 null、dailyCapacity null 或 ≥0（0 与 null 语义均为不限容，对齐 F-111 拦截条件 &gt;0 才拦）、
     * accepting ∈ {0,1}。{@code owner_user_id/contact_phone/photos/lng/lat} 不可改——body 带了忽略
     * （Controller 在响应 message 注明），Service 的落库白名单不含这些列。
     * 成功后写审计 {@code store.update}（before/after 只记实际变化的键）。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateStore(Long id, AdminStoreUpdateDTO dto, String actor);

    // ==================================================================
    // F-118 结算跑批 + 结算中心（三个操作：生成账期 / 账期列表 / 账单明细）。
    // 读端点不写审计；generate 成功后写 admin_audit_log：settlement.create。
    // 红线（契约 §3）：settlement 只增不改——本组无 UPDATE/DELETE settlement 路径，
    // 冲销走下一期 refund 列承载（06 册 §4.5 定稿）；主理人佣金（F-119）/打款（轨道 B）/
    // 保证金（m1x）零涉及；下单/取消/支付链路零改动（本组只读它们产生的数据）。
    // ==================================================================

    /**
     * 生成账期（{@code POST /api/admin/settlements/generate}，F-118 §1.1）。
     *
     * <p><b>校验顺序即报错优先级</b>：① merchantId 缺失/日期形状非法 → 400（请求自身缺陷，先于查库）→
     * ② merchant 不存在 → 404「商家不存在」→ ③ start≥end → 400 →
     * ④ 与该商家既有账期完全相同期（uk_merchant_period 预查，并发兜底 catch DuplicateKeyException）
     * → 400「该商家此账期已生成」（幂等护栏）→ ⑤ 聚合候选明细
     * （{@code OrderItemMapper#selectSettleCandidates}：supply_price 非空 + settle_id 空 +
     * pay_status=1 + 非 shortage，契约 §1.1 逐字），空集 → 400「该商家本期无可结算明细」（不生成空账单）。
     *
     * <p><b>金额</b>：gross=Σ(supply_price×qty)；refund=冲销——自上一账期止之后发生退款
     * （pay_status 1→2）的已挂锚明细负向计入本期（无账期历史则 0）；retain 恒 0（轨道 B 列位保留）；
     * net=gross−refund−retain。
     *
     * <p><b>事务与幂等（06 册 §4.5 定稿照抄）</b>：{@code @Transactional} 内
     * INSERT settlement（status=accounted，settle_no="SET"+雪花）→ 挂锚条件 UPDATE
     * （{@code OrderItemMapper#attachSettle}），<b>影响行数=候选数才成功</b>，
     * 不足即抛 400 整单回滚（并发跑批双开只有一个成功，另一个全回滚）。
     *
     * <p>成功后写审计 {@code settlement.create}（含 merchant/期/三金额/明细数）。
     *
     * @return {@code {id, settleNo, merchantId, merchantName, periodStart, periodEnd,
     *         gross, refund, net, itemCount, orderCount, status}}（页面与 verify 脚本断言用）
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    Map<String, Object> generateSettlement(AdminSettlementGenerateDTO dto, String actor);

    /**
     * 账期列表（{@code GET /api/admin/settlements?merchantId=}，F-118 §1.2）：全量或按商家筛
     * （merchantId 可空，空=全量；非空只做过滤不校验存在——照 listStores 全量先例，查无即空列表），
     * 按 id DESC（新账期在前）。每单键序固定
     * {@code id,settleNo,merchantId,merchantName,periodStart,periodEnd,gross,refund,retain,net,
     * itemCount,status,reconcileOk,paidChannelAbsent}（契约逐字十四键，不加 orderCount）。
     *
     * <p><b>reconcileOk 实时算</b>（不落列）：① 账面 gross==该单挂锚明细 Σ(supply_price×qty)
     * <b>且</b> ② 账面计数==实时挂锚计数 <b>且</b> ③ 挂锚明细无孤儿（orders 行被删即数据事故，
     * 2026-10-25 主会话验收裁决第三条件），三者皆真才 true（{@code OrderItemMapper#selectAnchoredAggregates}
     * 一次批量聚合，无 N+1）。settlement 表（06 册 §4.5 定稿）无 item_count 列，明细行数账面无落点——
     * 计数对账以表内唯一计数列 order_count 对实时去重订单数承载；出参 {@code itemCount} 取实时挂锚行数
     * （明细弹窗行数与它天然一致）。
     * <b>paidChannelAbsent 恒 true</b>：「打款通道未接」是 UI 恒显语义，接口层带出防前端漏显。
     */
    List<Map<String, Object>> listSettlements(Long merchantId);

    /**
     * 账单明细（{@code GET /api/admin/settlements/{id}/items}，F-118 §1.3）：
     * 该单挂锚的 order_item 行 {@code {itemId,dishName,supplyPrice,qty,subtotal,orderId,
     * payStatus,fulfillStatus,orphan}}，只读；结算单不存在 → 404「结算单不存在」。
     * pay_status 经 LEFT JOIN orders 取，孤儿行（orders 已删）payStatus=null、orphan=true
     * （2026-10-25 主会话验收裁决：不静默丢行，前端标「订单已不存在」警示）。
     */
    List<Map<String, Object>> settlementItems(Long id);
}
