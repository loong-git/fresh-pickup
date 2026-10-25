package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantDishCreateDTO;
import com.fresh.dto.MerchantDishOnSaleDTO;
import com.fresh.dto.MerchantDishStockDTO;
import com.fresh.dto.MerchantDishUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.service.MerchantDishService;
import com.fresh.service.MerchantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 商家端商品服务实现（F-13/G-02 Step 1+2）。
 *
 * R3' 归属铁律的本类落地方式（三条，逐条可 grep 核验）：
 * 1) 列表读：merchant_id 等值恒为 wrapper 第一个条件，其余 tab/category/keyword 只在其后叠加；
 * 2) 单行读写：一律经 findOwned()/`.eq(Dish::getId,..).eq(Dish::getMerchantId,..)` 双条件，
 *    写路径的影响行数判定（rows==0 → 404）就是授权判定本身——绝不在内存里比归属；
 * 3) 审计取旧值所需的读也走同一条双条件查询，不存在 selectById(dishId) 这类无归属读
 *    （反面教材 AdminServiceImpl#updateDish 对任意 dishId 生效，本类不复制该姿势）。
 *
 * 404 同文案「商品不存在」：他商家商品与不存在的商品不可区分，防存在性成为可枚举信号（契约 F-13.2 错误码口径）。
 */
@Slf4j
@Service
public class MerchantDishServiceImpl implements MerchantDishService {

    /**
     * 审计 detail 列宽：admin_audit_log.detail VARCHAR(500)（见 init.sql 的 admin_audit_log.detail 列）。MySQL 严格模式
     * （STRICT_TRANS_TABLES）下超长 INSERT 报 1406，而 writeAudit 是只 log.warn 的旁路 →
     * **业务写照常提交、审计行凭空消失**，且脏值一旦进了 dish.name，dishBrief 的 old 侧从此每次更新都带它，
     * 这一行商品永久脱审且外部不可发现（F-13.10 #2 实测 0→0 行）。列宽与合法性由 buildAuditDetail 收口。
     */
    private static final int AUDIT_DETAIL_MAX = 500;

    /**
     * Jackson 序列化最坏膨胀倍数：一个控制码（U+0001）→ 六字符转义串（反斜杠 + u0001）共 **6** 字符。
     * 旧注释写的是「最坏 2 倍」并据此把 old/new 各截到 100 字符宣称「恒为合法 JSON 且必在列宽内」——
     * 该前提被 F-13.10 #2 直接证伪（100 个 U+0001 序列化后 602 字符 > 500）。
     * 结论：按「原值截到多少字符才安全」推预算是**无解**的（膨胀倍数取决于字符种类），
     * 所以 BE-2 把保证挪到「先序列化、再按剩余预算整体截断」，本常数只用作降级路径的推导依据。
     */
    private static final int JSON_ESCAPE_FACTOR = 6;

    /**
     * 单侧原值的宽松上限（不再是「列宽安全线」，见 JSON_ESCAPE_FACTOR 注释）：
     * 目的只有两个——① 不把 MB 级 description 白白喂进序列化；② 让常规值（中文，膨胀系数≈1）
     * 在不触发降级的前提下尽量多留几列（dishBrief 补到 12 列后约 200~250 字符，100 会把它切残）。
     * 最坏情形 400 原字符 × 6 = 2400 序列化字符 → 必然走 buildAuditDetail 的降级截断，但**仍落一行**。
     */
    private static final int AUDIT_VALUE_MAX = 400;

    /** 兜底 detail：两侧都装不下时落这一行——「审计行必须存在」优先于「审计行有内容」（BE-2 的硬要求） */
    private static final String AUDIT_MINIMAL_DETAIL = "{\"old\":\"audit truncated\",\"new\":\"audit truncated\"}";

    /**
     * 商品图入库的唯一非空形态前缀：/static/images/ 覆盖平台静态件与上传落盘件
     * （/static/images/dish-N.svg 与 /static/images/uploads/&lt;名&gt;，F-13/G-02 决策①方案 A）。
     * <p>空串=清空该列（C 端退回 emoji/占位兜底，F-13.6bis #21 收窄后的口径），是白名单的另一形态，
     * 故本类不再为它单独设常量。
     * <p>/uploads/（CorsConfig 对同一物理目录注册的第一条映射，:68；:70 才是 /static/images/uploads/**）已从白名单剔除，见
     * assertDisplayValue 的 BE-9 说明——它是商家端 dev 预览的显示层形态，不是入库形态。
     */
    private static final String IMAGE_PREFIX_STATIC = "/static/images/";

    /**
     * 卡片背景色形态白名单（BE-4① / F-13.10 #4）：# + 3/6/8 位十六进制，或空串/null。
     * 为什么 @Size(max=20) 挡不住：C 端 front/pages/detail/detail.vue 的 slide-poster 用 **background 简写**绑 g.bg（由 dish.bgColor 衍生），
     * `url(//a.co/x)` 只有 12 字符、是合法 CSS → 商家可把任意外链塞进背景并让全站访客去请求它。
     * 正则与商家端 front-merchant/pages/dish-edit/dish-edit.vue 的 safeColor（computed）同一字符集
     * （那边 dish-edit.vue 的 HEX_COLOR_RE 已写成 /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/，本处同钉 3|6|8 三档、用 CASE_INSENSITIVE 覆盖它的 A-F，
     *  否则前端色块预览通过、后端 400 的形态会让商家一头雾水；放行的取值集合仍是纯十六进制，无 CSS 注入面）。
     */
    private static final Pattern BG_COLOR_PATTERN =
            Pattern.compile("^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$", Pattern.CASE_INSENSITIVE);

    /** 审计 detail 序列化用（Jackson ObjectMapper 线程安全，静态复用同 AdminServiceImpl#AUDIT_MAPPER 姿势） */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private MerchantService merchantService;
    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Override
    public List<Dish> list(Long merchantId, String tab, String category, String keyword) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        String kw = keyword == null ? "" : keyword.trim();
        boolean hasCategory = category != null && !category.isEmpty();
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getMerchantId, merchantId)          // R3'：恒定第一个条件，任何分支都摘不掉
                .eq("on_sale".equals(tab), Dish::getOnSale, 1)
                .eq("off_sale".equals(tab), Dish::getOnSale, 0)
                // tab=all / 缺省 / 未知取值都不加 on_sale 条件：未知值按 all 兜底，
                // 既不放空集也不新增 400 文案（R2 码表文案零改动），且仍在归属范围内，无泄露面
                .eq(hasCategory, Dish::getCategory, category)
                // 商家侧 keyword 只做名称 LIKE（契约口径修正③）：C 端的 MATCH AGAINST/别名是搜索语义，
                // 商家找自己的货不需要，且那条链路是 dishMapper 自写 SQL，会绕开归属条件
                .like(!kw.isEmpty(), Dish::getName, kw)
                .orderByAsc(Dish::getId));
    }

    @Override
    public Dish detail(Long merchantId, Long id) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        Dish dish = findOwned(id, merchantId);
        if (dish == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }
        return dish;
    }

    @Override
    @Transactional
    public Dish create(Long userId, Long merchantId, MerchantDishCreateDTO dto) {
        merchantService.assertActive(merchantId);
        // BE-3/BE-4 值形态收口（create 侧 name/unit/category 已被 @NotBlank 拦过，这里同判定命中不了、纯属共用一处）
        assertDisplayValue(dto.getImage(), dto.getBgColor(), dto.getName(), dto.getUnit(), dto.getCategory());

        Dish dish = new Dish();
        dish.setName(dto.getName());
        dish.setPrice(dto.getPrice());
        dish.setImage(dto.getImage());
        dish.setDescription(dto.getDescription());
        dish.setCategory(dto.getCategory());
        dish.setUnit(dto.getUnit());
        dish.setStock(dto.getStock());
        dish.setEmoji(dto.getEmoji());
        dish.setBgColor(dto.getBgColor());
        dish.setTags(dto.getTags());
        dish.setLimitBuy(dto.getLimitBuy());
        // 归属唯一来源：服务端 @RequestAttribute 解析出的 merchantId（DTO 无 merchantId/id 字段，R5'）
        dish.setMerchantId(merchantId);
        dishMapper.insert(dish);

        // 回查而非直接返回入参实体：on_sale/seckill/sold_count/good_rate/create_time 等列由 DB DEFAULT
        // 与种子公式生成（新建默认 on_sale=1 上架，见 init.sql 的 dish.on_sale 列与本类边界回报），商家端列表要读这些列；
        // 回查本身也走 id + merchant_id 双条件（R3'，不给无归属读留口子）
        Dish created = detail(merchantId, dish.getId());
        log.info("商家端新增商品: merchantId={}, dishId={}, name={}, price={}, stock={}",
                merchantId, created.getId(), dto.getName(), dto.getPrice(), dto.getStock());
        writeAudit("merchant:" + userId, "dish.create", "dish", String.valueOf(created.getId()),
                null, dishBrief(created).toString());
        return created;
    }

    @Override
    @Transactional
    public void update(Long userId, Long merchantId, Long id, MerchantDishUpdateDTO dto) {
        merchantService.assertActive(merchantId);
        if (dto.getStock() != null) {
            // R7'：商家端不存在精确 set 库存的路径（一期只开增量，精确 set 归 Admin）。
            // 显式 400 指路而不是静默忽略——静默丢弃会让调用方以为改成功（决策①点名的静默假成功反模式）
            throw new BizException(R.CODE_BAD_REQUEST, "库存不能直接填最终值，请用库存调整接口按增减数量修改");
        }
        // BE-3/BE-4 值形态收口（与 create 共用一处私有方法）：image 外链 / bgColor 的 CSS 简写注入 /
        // name·unit·category 的「非 null 但 blank」清空，全部在写库之前 400（F-13.10 #3 #4 #6）
        assertDisplayValue(dto.getImage(), dto.getBgColor(), dto.getName(), dto.getUnit(), dto.getCategory());

        // 写路径母版 AdminServiceImpl#updateDish（逐字段 set + 全空 400），在其之上必须追加归属条件（R3'）
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>()
                .eq(Dish::getId, id)
                .eq(Dish::getMerchantId, merchantId);
        int setCount = 0;
        if (dto.getName() != null) {
            wrapper.set(Dish::getName, dto.getName());
            setCount++;
        }
        if (dto.getPrice() != null) {
            wrapper.set(Dish::getPrice, dto.getPrice());
            setCount++;
        }
        if (dto.getImage() != null) {
            wrapper.set(Dish::getImage, dto.getImage());
            setCount++;
        }
        if (dto.getDescription() != null) {
            wrapper.set(Dish::getDescription, dto.getDescription());
            setCount++;
        }
        if (dto.getCategory() != null) {
            wrapper.set(Dish::getCategory, dto.getCategory());
            setCount++;
        }
        if (dto.getUnit() != null) {
            wrapper.set(Dish::getUnit, dto.getUnit());
            setCount++;
        }
        if (dto.getEmoji() != null) {
            wrapper.set(Dish::getEmoji, dto.getEmoji());
            setCount++;
        }
        if (dto.getBgColor() != null) {
            wrapper.set(Dish::getBgColor, dto.getBgColor());
            setCount++;
        }
        if (dto.getTags() != null) {
            // tags 不走 wrapper.set，改由下方 patch 实体承载：MP 的 wrapper SET 是手拼的
            // #{ew.paramNameValuePairs..} 片段，不带 @TableField(typeHandler=JacksonTypeHandler) 的列元信息，
            // List<String> 会退化成未知类型交给 JDBC 驱动绑定而报错；走 entity 路径才有自动生成的映射。
            // 归属条件不受影响——WHERE 全部来自 wrapper 的 id + merchant_id，entity 只贡献 SET 列
            setCount++;
        }
        if (dto.getLimitBuy() != null) {
            wrapper.set(Dish::getLimitBuy, dto.getLimitBuy());
            setCount++;
        }
        if (setCount == 0) {
            throw new BizException("无更新字段（可传 name / price / image / description / category / unit / emoji / bgColor / tags / limitBuy）");
        }
        // tags 用 patch 实体承载（见上方 tags 分支注释），其余列仍走 wrapper.set 保持母版形态；
        // 未传 tags 时 patch=null，即纯 wrapper SET，与本类的其余写路径同姿势
        Dish patch = null;
        if (dto.getTags() != null) {
            patch = new Dish();
            patch.setTags(dto.getTags());
        }

        // 旧值快照（F-13.2 行 4「detail 记旧值→新值」）：读的本身也带 merchant_id，且它不是授权判定——
        // 真正的判定是下面 UPDATE 的 WHERE，两处任一查不到都落到同一个 404 文案
        Dish before = findOwned(id, merchantId);
        if (before == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }
        // F-91：秒杀件的挂牌价归运营。成交单价恒取 seckill_price，但 C 端秒杀件的**划线价就是 dish.price**、
        // 折扣率按 (price − seckill_price)/price 换算——所以商家抬高 price 并不改动收款额，而是把折扣凭空放大，
        // 危害落在顾客看到的价签上。只拦 price 一列：把整条 update 关掉会顺手剥夺商家改标题/换图的能力，
        // 那是另一个决策。守卫必须落在下面的 update 之前（单测用 verify(never()) 钉住这个顺序）。
        // seckill 实测是 Integer 且可空（Dish#seckill / dish.seckill 列），故写 equals 不写 ==，后者在 null 时拆箱 NPE→500。
        if (dto.getPrice() != null && Integer.valueOf(1).equals(before.getSeckill())) {
            throw new BizException(R.CODE_BAD_REQUEST,
                    "该商品正在参加平台秒杀活动，挂牌价由运营统一维护，如需调价请联系运营");
        }
        if (dishMapper.update(patch, wrapper) == 0) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }

        StringBuilder newVal = appendField(appendField(new StringBuilder(), "name", dto.getName()), "price", dto.getPrice());
        appendField(newVal, "category", dto.getCategory());
        appendField(newVal, "unit", dto.getUnit());
        appendField(newVal, "limitBuy", dto.getLimitBuy());
        appendField(newVal, "image", dto.getImage());
        appendField(newVal, "description", dto.getDescription() != null ? "len=" + dto.getDescription().length() : null);
        appendField(newVal, "tags", dto.getTags());
        appendField(newVal, "emoji", dto.getEmoji());
        appendField(newVal, "bgColor", dto.getBgColor());
        log.info("商家端更新商品: merchantId={}, dishId={}, changed={}", merchantId, id, newVal);
        writeAudit("merchant:" + userId, "dish.update", "dish", String.valueOf(id),
                dishBrief(before).toString(), newVal.toString());
    }

    @Override
    @Transactional
    public Integer adjustStock(Long userId, Long merchantId, Long id, MerchantDishStockDTO dto) {
        merchantService.assertActive(merchantId);
        Integer delta = dto.getDelta();
        if (delta == null) {
            // 纵深防御：@NotNull + @Valid 已在 Controller 挡下，此处兜住内部直调——
            // 下面要拿它做取负与拼接，null 走到那里是 NPE→500，不如同文案 400
            throw new BizException(R.CODE_BAD_REQUEST, "请填写库存调整数量");
        }
        Dish before = findOwned(id, merchantId);
        if (before == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }

        // R7' 增量 UPDATE（禁读-改-写）：SET 交给 DB 在语句内算完，并发两笔 +50 各自读到行锁后累加，
        // 结果恰为 +100。拼进 SET 的 delta 是 Integer（取值仅 -?数字，无引号无空白），
        // 且已被 @Min(-1000)/@Max(1000) 界定，不存在任何字符串入参参与 SQL 文本；
        // 外层括号是防御性包裹（杜绝与后续可能追加的 SET 片段发生运算符歧义）
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>()
                .eq(Dish::getId, id)
                .eq(Dish::getMerchantId, merchantId)
                .setSql("stock = stock + (" + delta + ")");
        if (delta < 0) {
            // 不减成负数：stock >= -delta 与 stock + delta >= 0 同解，但前者是纯 lambda 列条件、
            // 值经 MP 参数绑定（#{ew.paramNameValuePairs..}）而非拼进 SQL 文本，连一段裸 SQL 片段都省掉；
            // -delta 最大 1000，不存在 Integer.MIN_VALUE 取负溢出
            wrapper.ge(Dish::getStock, -delta);
        }
        if (dishMapper.update(null, wrapper) == 0) {
            // 连接串未开 useAffectedRows → 返回 matched rows（同值更新也计 1），故 0 行只有两种成因：
            // 行不归属/已不存在，或库存不够减。用同一条双条件查询区分，不改变「他商家 = 404」的对外口径
            Dish current = findOwned(id, merchantId);
            if (current == null) {
                throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
            }
            throw new BizException(R.CODE_BAD_REQUEST, "库存不足，不能减成负数");
        }

        // 返回 DB 真实库存而非 before+delta：并发下 before 只是估算值，出参必须是落库后的权威值
        Dish after = detail(merchantId, id);
        log.info("商家端调整库存: merchantId={}, dishId={}, delta={}, {} -> {}",
                merchantId, id, delta, before.getStock(), after.getStock());
        writeAudit("merchant:" + userId, "dish.stock", "dish", String.valueOf(id),
                appendField(new StringBuilder(), "stock", before.getStock()).toString(),
                appendField(appendField(new StringBuilder(), "delta", delta), "stock", after.getStock()).toString());
        return after.getStock();
    }

    @Override
    @Transactional
    public void setOnSale(Long userId, Long merchantId, Long id, MerchantDishOnSaleDTO dto) {
        merchantService.assertActive(merchantId);
        Dish before = findOwned(id, merchantId);
        if (before == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }
        int rows = dishMapper.update(null, new LambdaUpdateWrapper<Dish>()
                .eq(Dish::getId, id)
                .eq(Dish::getMerchantId, merchantId)
                .set(Dish::getOnSale, dto.getOnSale()));
        if (rows == 0) {
            throw new BizException(R.CODE_NOT_FOUND, "商品不存在");
        }
        log.info("商家端上下架: merchantId={}, dishId={}, onSale: {} -> {}",
                merchantId, id, before.getOnSale(), dto.getOnSale());
        writeAudit("merchant:" + userId, "dish.onsale", "dish", String.valueOf(id),
                appendField(new StringBuilder(), "onSale", before.getOnSale()).toString(),
                appendField(new StringBuilder(), "onSale", dto.getOnSale()).toString());
    }

    /** R3' 单行读的唯一封装：WHERE id=? AND merchant_id=?（他商家与不存在同为 null，由调用方统一转 404） */
    private Dish findOwned(Long id, Long merchantId) {
        return dishMapper.selectOne(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getId, id)
                .eq(Dish::getMerchantId, merchantId));
    }

    /**
     * 展示字段值形态收口（BE-3 + BE-4① ②，create/update 两个写入口共用这一处，不散成两处）：
     * <p><b>image</b>（F-13.10 #3，high）：商家可控的 image 会被 C 端 &lt;image :src="dish.image"&gt; 原样吐给
     * 每个访客，实测 `PUT /dishes/34 {"image":"https://tracker.example.com/p.gif?m=34"}` 200 入库、
     * `GET /api/dishes/34` 原样回显 → 受害面是全站访客（IP/UA/Referer 外流）不是商家自己，`data:` 亦可写。
     * 故只放行两种形态：空串（清空）、/static/images/（平台静态件，含上传落盘前缀 /static/images/uploads/）。
     * <p><b>BE-9 收紧（本批）**：/uploads/ 从白名单剔除。它是 CorsConfig 对同一目录注册的第一条映射（:68）、
     * 只服务商家端 dev 预览（商家端跑独立 origin，C 端静态根路径在它那里取不到文件），而 C 端
     * &lt;image :src="dish.image"&gt; **不拼 BASE_URL**（front/pages/index/index.vue:118、detail.vue:23 即此形态）——
     * 库里存了 /uploads/xxx.jpg 时，dev 下 5173 把它当自己的路由走 SPA 回退、prod 下 nginx try_files 同样
     * 回 index.html，浏览器拿到的是 text/html 不是图片，@error 触发 → C 端**静默退回 emoji 兜底**。
     * 症状「上传成功、库里有值、C 端看起来没图」正是决策①点名的静默假成功形态，且入库侧留着这一档，
     * 任何调用方（含 Admin 之外的脚本）存错前缀都不会得到反馈。商家端前端只写上传出参的 path 键
     * （front-merchant/pages/dish-edit/dish-edit.vue 的 uploadImage 只取 data.path），显示另经 api/index.js#merchantFileUrl 换前缀，
     * 故剔除的回归面为零；dev 库实测 dish.image 亦无一行以 /uploads/ 开头（无需回填清洗）。
     * <p><b>bgColor</b>（BE-4①）：见 BG_COLOR_PATTERN 注释——background 简写让 12 字符的 url(...) 过了 @Size(20)。
     * <p><b>name/unit/category</b>（BE-4② / F-13.10 #6）：update 的部分更新语义把「非 null 但 blank」当成
     * 合法的清空指令，实测 `PUT /dishes/35 {"category":"","unit":"","name":""}` 三列全变空串；
     * NOT NULL 列被写成空值等于把 C 端卡片渲染成无名无单位。create 侧这三列本就 @NotBlank，同一判定命中不了。
     * <p>文案一律复用既有串（R2 硬红线，不新增 400/403 文案）：形态不合法走
     * GlobalExceptionHandler:55 的「请求参数有误，请检查后重试」；三列 blank 走 MerchantDishCreateDTO
     * 里同字段的 @NotBlank message 原文（「商品名称不能为空」「请填写商品单位」「请选择商品分类」）。
     * <p>null（未传）不参与判定：image/bgColor 为 null 是「本次不改这列」，name/unit/category 同理。
     */
    private void assertDisplayValue(String image, String bgColor, String name, String unit, String category) {
        if (image != null && !image.isEmpty() && !image.startsWith(IMAGE_PREFIX_STATIC)) {
            throw new BizException(R.CODE_BAD_REQUEST, "请求参数有误，请检查后重试");
        }
        if (bgColor != null && !bgColor.isEmpty() && !BG_COLOR_PATTERN.matcher(bgColor).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, "请求参数有误，请检查后重试");
        }
        if (isBlank(name)) {
            throw new BizException(R.CODE_BAD_REQUEST, "商品名称不能为空");
        }
        if (isBlank(unit)) {
            throw new BizException(R.CODE_BAD_REQUEST, "请填写商品单位");
        }
        if (isBlank(category)) {
            throw new BizException(R.CODE_BAD_REQUEST, "请选择商品分类");
        }
    }

    /** 非 null 但去掉首尾空白后为空——部分更新语义下这是「要把 NOT NULL 列写成空」的信号，必须拦 */
    private boolean isBlank(String value) {
        return value != null && value.trim().isEmpty();
    }

    /**
     * 写闸门已抽到 MerchantService#assertActive（BE-5 / F-13.10 #5）：本类四个写端点与
     * MerchantController#upload、MerchantServiceImpl#updateProfile 共用同一处判定，
     * 行为与本类旧 assertWritable 逐字一致（同 403 R.MSG_FORBIDDEN、同 404「商家不存在」、
     * 同首行 merchantId 判空的纵深防御），只是不再各写一份。
     */

    // 写闸门已抽到 MerchantService#assertActive（BE-5 / F-13.10 #5）：本类四个写端点与
    // MerchantController#upload、MerchantServiceImpl#updateProfile 共用同一处判定，
    // 行为与本类旧 assertWritable 逐字一致（同 403 R.MSG_FORBIDDEN、同 404「商家不存在」、
    // 同首行 merchantId 判空的纵深防御），只是不再各写一份。

    /**
     * 审计落库（R6'，契约随建随写）：actor=merchant:&lt;userId&gt;，与 admin:&lt;key 摘要&gt; 同表不同前缀。
     * 审计是旁路——写失败绝不打断主事务（同 AdminServiceImpl#writeAudit 纪律）。
     * <p>BE-2 的修正点：**失败也要再落一行**。旧实现是「一条 INSERT + catch 里 log.warn」，
     * 于是超长（1406）或孤立代理（1366）都表现为「业务写照常提交、审计行凭空消失」，
     * 且脏值留在 dish.name 里 → dishBrief 的 old 侧每次更新都带它 → 该商品永久脱审且外部不可发现。
     * 现在第一趟用完整 detail，任一层原因失败即退成 AUDIT_MINIMAL_DETAIL 再写一次，两趟都失败才 warn。
     * <p>自带一份不复用 AdminServiceImpl 的私有方法：那是 private，跨类复用就得把它降为公开，属越界改动。
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String oldVal, String newVal) {
        String detail = buildAuditDetail(oldVal, newVal);
        try {
            insertAudit(actor, action, targetType, targetId, detail);
            return;
        } catch (Exception e) {
            log.warn("审计写入失败（旁路，不影响主事务），改用最小 detail 重试: actor={}, action={}, targetType={}, targetId={}",
                    actor, action, targetType, targetId, e);
        }
        try {
            insertAudit(actor, action, targetType, targetId, AUDIT_MINIMAL_DETAIL);
        } catch (Exception e) {
            log.warn("审计重试仍失败（旁路，不影响主事务）: actor={}, action={}, targetType={}, targetId={}",
                    actor, action, targetType, targetId, e);
        }
    }

    private void insertAudit(String actor, String action, String targetType, String targetId, String detail) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActor(actor);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail);
        row.setIp(currentIp());
        adminAuditLogMapper.insert(row);
    }

    /**
     * detail 组装（BE-2）：**先序列化、再按剩余预算整体截断**，产出必落在 VARCHAR(500) 内且仍是合法 JSON
     * （JSON_VALID(detail)=1 是 F-13.5 DoD⑦ 的硬断言）。
     * <p>常规值（中文名/描述，Jackson 膨胀系数≈1）两侧都原样保留；一旦超限（控制码 6 倍膨胀最易触发，
     * 见 JSON_ESCAPE_FACTOR）就走 F-13.10 #2 的降级形态 {"old":"truncated","new":&lt;截到预算内&gt;}：
     * old 侧塌成一个定长串，把预算让给「本次到底改了什么」的 new 侧；new 侧仍装不下时按 clipJsonLiteral
     * 的 token 边界截。走到这里任何分支都不可能再超出列宽，兜底仍落一行。
     */
    private String buildAuditDetail(String oldVal, String newVal) {
        String oldJson = jsonValue(dropUnpairedSurrogates(truncate(oldVal)));
        String newJson = jsonValue(dropUnpairedSurrogates(truncate(newVal)));
        String detail = "{\"old\":" + oldJson + ",\"new\":" + newJson + "}";
        if (detail.length() <= AUDIT_DETAIL_MAX) {
            return detail;
        }
        String head = "{\"old\":\"truncated\",\"new\":";
        String clippedNew = clipJsonLiteral(newJson, AUDIT_DETAIL_MAX - head.length() - 1);
        if (clippedNew != null) {
            return head + clippedNew + "}";
        }
        return AUDIT_MINIMAL_DETAIL;
    }

    /** 审计值拼串：key=value 逗号分隔（null 值原样写成 null，与 AdminServiceImpl 的 detail 显式留痕同口径） */
    private StringBuilder appendField(StringBuilder sb, String key, Object value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        return sb.append(key).append('=').append(value);
    }

    /**
     * 旧值快照的列集与 update 的 new 侧**逐一对齐**（BE-4③ / F-13.10 #11）：旧实现只有
     * name/price/stock/onSale/category/unit/limitBuy 七列，答不出「这张图是谁换的」——
     * 而 new 侧 (:262-270) 明明记了 image/description 长度/tags/emoji/bgColor。
     * description 取长度摘要而非全文（列是 TEXT，全量会把 500 预算一口吃光；长度差足以说明改没改）；
     * stock/onSale 是 new 侧没有的两列，保留是因为 create 的 detail 与 dish.stock/dish.onsale 审计也走本方法。
     * 超长由 buildAuditDetail 的预算截断兜住，不再靠「少记几列」迁就列宽。
     */
    private StringBuilder dishBrief(Dish dish) {
        StringBuilder sb = appendField(appendField(new StringBuilder(), "name", dish.getName()), "price", dish.getPrice());
        appendField(sb, "stock", dish.getStock());
        appendField(sb, "onSale", dish.getOnSale());
        appendField(sb, "category", dish.getCategory());
        appendField(sb, "unit", dish.getUnit());
        appendField(sb, "limitBuy", dish.getLimitBuy());
        appendField(sb, "image", dish.getImage());
        appendField(sb, "description", dish.getDescription() != null ? "len=" + dish.getDescription().length() : null);
        appendField(sb, "tags", dish.getTags());
        appendField(sb, "emoji", dish.getEmoji());
        appendField(sb, "bgColor", dish.getBgColor());
        return sb;
    }

    /**
     * 原值宽松上限（不是列宽安全线，理由见 AUDIT_VALUE_MAX 注释）。
     * 截点按 codePoint 退到整字符边界：末尾是高代理（对的第二半被截掉）或前一半已被截掉的孤立低代理都退，
     * 剩下的成对代理不受影响。截完再经 dropUnpairedSurrogates 兜住原值本身就带孤立代理的情形。
     */
    private String truncate(String value) {
        if (value == null || value.length() <= AUDIT_VALUE_MAX) {
            return value;
        }
        int end = AUDIT_VALUE_MAX;
        // 尾部高代理 → 必须退（它后面配对的低代理被截掉了）
        while (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        // 截点前一位若是低代理，且它配对的高代理落在截点之外 → 也是劈开代理对，退到它前面
        while (end > 0 && Character.isLowSurrogate(value.charAt(end - 1))
                && !(end >= 2 && Character.isHighSurrogate(value.charAt(end - 2)))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 把「已序列化的 JSON 值」截到 maxLen 字符内且仍是合法 JSON 字符串。
     * 按 token 边界前进扫描（\\uXXXX 6 字符、\\x 2 字符、代理对 2 字符），截点绝不落在转义序列
     * 或代理对中间——否则截出来的是不可解析 JSON，或 utf8mb4 存不下的孤立代理（BE-2 的另一个丢行成因）。
     * 不可截（预算小于一个空串字面量、或值本身是 null 字面量）时返回 null，交调用方走最小 detail 兜底。
     */
    private String clipJsonLiteral(String literal, int maxLen) {
        if (literal == null || literal.length() <= maxLen) {
            return literal;
        }
        if (literal.length() < 2 || literal.charAt(0) != '"' || maxLen < 2) {
            return null;
        }
        int bodyEnd = maxLen - 1;                 // 末位留给收尾双引号
        int i = 1;
        int safeEnd = 1;
        while (i < bodyEnd) {
            char c = literal.charAt(i);
            int step = 1;
            if (c == '\\') {
                step = (i + 1 < literal.length() && literal.charAt(i + 1) == 'u') ? 6 : 2;
            } else if (Character.isHighSurrogate(c)) {
                step = 2;
            }
            if (i + step > bodyEnd) {
                break;
            }
            i += step;
            safeEnd = i;
        }
        return literal.substring(0, safeEnd) + "\"";
    }

    /**
     * 孤立代理项换成 U+FFFD：商家可控的 name/description 里塞一个不成对的 \\uD800/\\uDC00 时，
     * Jackson 走 Writer 序列化是**原样透传**（不经 UTF-8 编码，所以不报错），但 utf8mb4 列存不下它 →
     * MySQL 1366 → 整行审计消失（与超长 1406 同一类「投毒一次即永久脱审」，故与预算截断并列收口）。
     */
    private String dropUnpairedSurrogates(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                if (sb != null) {
                    sb.append(c).append(value.charAt(i + 1));
                }
                i++;                            // 合法代理对：两个一起走
                continue;
            }
            if (!Character.isSurrogate(c)) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;                       // BMP 内普通字符
            }
            if (sb == null) {
                sb = new StringBuilder(value.length());
                sb.append(value, 0, i);
            }
            sb.append('\uFFFD');
        }
        return sb == null ? value : sb.toString();
    }

    /**
     * detail 值序列化：null → null，其余交 Jackson writeValueAsString 全量转义
     * （F-12 S-4 结论沿用：手写 replace 只挡反斜杠/双引号，挡不住 \n、\r 与控制码，写出的会是不可解析 JSON）。
     */
    private String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return AUDIT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** 操作来源 IP：Service 层拿不到 request，走 RequestContextHolder 取 remoteAddr；非请求线程返回 null（列可空） */
    private String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
