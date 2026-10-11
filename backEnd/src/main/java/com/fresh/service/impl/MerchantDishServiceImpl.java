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
import com.fresh.entity.Merchant;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.service.MerchantDishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 商家端商品服务实现（F-13/G-02 Step 1+2）。
 *
 * R3' 归属铁律的本类落地方式（三条，逐条可 grep 核验）：
 * 1) 列表读：merchant_id 等值恒为 wrapper 第一个条件，其余 tab/category/keyword 只在其后叠加；
 * 2) 单行读写：一律经 findOwned()/`.eq(Dish::getId,..).eq(Dish::getMerchantId,..)` 双条件，
 *    写路径的影响行数判定（rows==0 → 404）就是授权判定本身——绝不在内存里比归属；
 * 3) 审计取旧值所需的读也走同一条双条件查询，不存在 selectById(dishId) 这类无归属读
 *    （反面教材 AdminServiceImpl#updateDish:136-140 对任意 dishId 生效，本类不复制该姿势）。
 *
 * 404 同文案「商品不存在」：他商家商品与不存在的商品不可区分，防存在性成为可枚举信号（契约 F-13.2 错误码口径）。
 */
@Slf4j
@Service
public class MerchantDishServiceImpl implements MerchantDishService {

    /**
     * 审计 detail 单值上限：admin_audit_log.detail 是 VARCHAR(500)（init.sql:319），而商家可控的
     * name/description/tags 加起来能远超列宽——MySQL 严格模式下超长 INSERT 直接失败，
     * 写失败的后果是 DoD⑦ 要求的审计留痕凭空消失（writeAudit 是旁路，不报错也没人知道）。
     * 故 old/new 各截到 100 字符再交 Jackson 转义：最坏全字符被转义成 2 倍长也不超 424，detail 恒为合法 JSON。
     */
    private static final int AUDIT_VALUE_MAX = 100;

    /** 审计 detail 序列化用（Jackson ObjectMapper 线程安全，静态复用同 AdminServiceImpl:64 姿势） */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private MerchantMapper merchantMapper;
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
        assertWritable(merchantId);

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
        // 与种子公式生成（新建默认 on_sale=1 上架，见 init.sql:70 与本类边界回报），商家端列表要读这些列；
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
        assertWritable(merchantId);
        if (dto.getStock() != null) {
            // R7'：商家端不存在精确 set 库存的路径（一期只开增量，精确 set 归 Admin）。
            // 显式 400 指路而不是静默忽略——静默丢弃会让调用方以为改成功（决策①点名的静默假成功反模式）
            throw new BizException(R.CODE_BAD_REQUEST, "库存不能直接填最终值，请用库存调整接口按增减数量修改");
        }

        // 写路径母版 AdminServiceImpl#updateDish:135-155（逐字段 set + 全空 400），在其之上必须追加归属条件（R3'）
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
        assertWritable(merchantId);
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
        assertWritable(merchantId);
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
     * 写闸门（F-13 决策④）：主体存在且 status=active 才放行写；suspended/terminated 只锁写侧，
     * 读路径（list/detail）不调本方法——停业商家仍要能看自己的货。
     * 首行 merchantId 判空是纵深防御（与 MerchantServiceImpl 同口径）：拦截器已保证进到这里的值恒非 null，
     * 但一旦有非 /api/merchant/** 前缀的内部调用绕过拦截器，null 落到 WHERE merchant_id=null 会静默空集。
     */
    private void assertWritable(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        if (!"active".equals(merchant.getStatus())) {
            // 403 用固定文案 R.MSG_FORBIDDEN（R2：不新增错误码也不改文案）；停业/清退的具体状态由
            // GET /api/merchant/me 的 status 字段供前端展示，不靠错误文案区分（避免新增可枚举信号）
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
    }

    /**
     * 审计落库（R6'，契约随建随写）：actor=merchant:&lt;userId&gt;，与 admin:&lt;key 摘要&gt; 同表不同前缀。
     * 审计是旁路——整体 try-catch，写失败只 log.warn，绝不抛异常打断主事务（同 AdminServiceImpl#writeAudit 纪律）。
     * 自带一份不复用 AdminServiceImpl 的私有方法：那是 private，跨类复用就得把它降为公开，属越界改动。
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String oldVal, String newVal) {
        try {
            AdminAuditLog row = new AdminAuditLog();
            row.setActor(actor);
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setDetail("{\"old\":" + jsonValue(truncate(oldVal)) + ",\"new\":" + jsonValue(truncate(newVal)) + "}");
            row.setIp(currentIp());
            adminAuditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("审计写入失败（旁路，不影响主事务）: actor={}, action={}, targetType={}, targetId={}",
                    actor, action, targetType, targetId, e);
        }
    }

    /** 审计值拼串：key=value 逗号分隔（null 值原样写成 null，与 AdminServiceImpl 的 detail 显式留痕同口径） */
    private StringBuilder appendField(StringBuilder sb, String key, Object value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        return sb.append(key).append('=').append(value);
    }

    /** 旧值快照只取关键短列：description/tags/image 等长文本不进审计（列宽 500 会被挤爆，全量变更已在 log.info 留服务端日志） */
    private StringBuilder dishBrief(Dish dish) {
        StringBuilder sb = appendField(appendField(new StringBuilder(), "name", dish.getName()), "price", dish.getPrice());
        appendField(sb, "stock", dish.getStock());
        appendField(sb, "onSale", dish.getOnSale());
        appendField(sb, "category", dish.getCategory());
        appendField(sb, "unit", dish.getUnit());
        return appendField(sb, "limitBuy", dish.getLimitBuy());
    }

    /** 截断到 AUDIT_VALUE_MAX；末尾若是高代理项再退一格，避免把 emoji 的代理对切成半个（Jackson 会报不可编码字符） */
    private String truncate(String value) {
        if (value == null || value.length() <= AUDIT_VALUE_MAX) {
            return value;
        }
        int end = AUDIT_VALUE_MAX;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
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
