package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品表 dish。T-M4-03 字段迁移：秒杀/销量/限购/标签/好评率六列后端真实存储直出，
 * 前端 decorateDish 的合成逻辑删除；tags 列存 JSON 数组串，经 JacksonTypeHandler 映射字符串数组。
 */
@Data
@TableName(value = "dish", autoResultMap = true) // tags 走 JacksonTypeHandler，查询需 autoResultMap 回映射
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal price;
    private String image;
    private String description;
    private String category;
    private String unit;
    private Integer stock;
    /** 是否在售（T-M3-09）：1 上架 / 0 下架，管理端可改 */
    private Integer onSale;
    /** F-116 审核态两枚值（m26 迁移列口径）：先发后审，驳回即 C 端不可见不可买，恢复唯一入口=运营端 restore */
    public static final String AUDIT_OK = "ok";
    public static final String AUDIT_REJECTED = "rejected";
    /**
     * P11-2 P3-F 新增第三枚值：软词命中待审（复用 m26 的 audit_status 列，VARCHAR(20) 无值域约束，零迁移）。
     * C 端可见性恒 {@code audit_status='ok'}（F-116 §2.1 四处读路径 eq(AUDIT_OK)），pending 品不进列表/搜索/
     * 秒杀/推荐；恢复唯一入口=运营端 restore（AdminServiceImpl#auditDish 置 ok 清 reason，reject 照旧可用）。
     * 红线：软词 pending 只影响商品 create/update 两个写入口，下单拦截/C 端出参等既有审核联动一字不动。
     */
    public static final String AUDIT_PENDING = "pending";
    /** 审核态（F-116/m26）：ok 正常 / rejected 运营驳回；与 on_sale 两列正交（驳回不改上下架，商家改品不写本列） */
    private String auditStatus;
    /** 驳回理由（F-116/m26）：reject 必填 1~200，restore 清空；商家端展示 */
    private String auditReason;
    /** 供货价真源（F-117/m27）：NULL=未设（不进结算聚合，F-118 聚合基数语义）；运营端维护，下单快照进 order_item。
     *  @JsonIgnore 照 merchantId 先例封 C 端出参——供货价是平台-商家核算维度（商家端展示在 F-120 走专门端点） */
    @JsonIgnore
    private BigDecimal supplyPrice;
    /** 秒杀标记（T-M4-03）：1 秒杀 / 0 普通；种子按旧前端合成规则 id%3==0 迁移 */
    private Integer seckill;
    /** 秒杀价（T-M4-03）：种子 ROUND(price*0.78,2)，非秒杀为 null */
    private BigDecimal seckillPrice;
    /** 销量（T-M4-03）：前端进度条按 soldCount/(soldCount+stock) 真实比例渲染 */
    private Integer soldCount;
    /** 限购数量（T-M4-03）：种子 [2,5,8] 按 id 循环，下单校验以此为准 */
    private Integer limitBuy;
    /** 卖点标签（T-M4-03）：种子按分类写死，DB 存 JSON 数组串、接口直出字符串数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;
    /** 好评率文案（T-M4-03）：如 94.4% */
    private String goodRate;
    private String emoji;
    @JsonIgnore
    private Long merchantId;
    private String bgColor;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ==================== P11-4 片① 秒杀窗口态展示键（纯出参瞬态，不映射任何列） ====================
    // 三键均 @TableField(exist = false)：MP 查询/写入不触碰；@JsonInclude(NON_NULL)：未填充即不出键
    // ——非秒杀件/未经装饰路径（商家端、运营端、推荐）出参与本片改动前逐字相同（对照 @JsonIgnore
    // 封 merchantId/supplyPrice 先例：那两键是封 C 端，这三键是 C 端展示新增，方向相反互不影响）。

    /** P11-4 片① D2：秒杀窗口态（服务端时钟判定，判定源 SeckillSessionResolver#inWindow 单一真源）。
     *  仅秒杀件由 DishServiceImpl 列表/详情出参装饰时下发（窗口内 true / 窗口外 false），
     *  前端不自判窗口（避免两端时钟/字典不同步）。D5 实时性口径：出参组装时刻一次采样，
     *  页面停留跨窗口不实时翻转，刷新/onShow 重取生效。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(exist = false)
    private Boolean seckillActive;

    /** P11-4 片① D3：秒杀场次开始时刻「HH:mm」（resolver.sessionTime("start", 默认)），
     *  仅秒杀件随 seckillActive 同点下发，详情页窗口外预告文案「每日 {seckillStartAt} 开抢」用；
     *  首页秒杀区窗口外预告走 /api/seckill/current 顶层 start（D3），不出本键。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(exist = false)
    private String seckillStartAt;

    /** P11-4 片① D1：秒杀窗口态项级键——/api/seckill/current 的 dishes 每项由 SeckillController 填充
     *  （窗口内 true / 窗口外 false），C 端首页秒杀卡按项切原价/预告态；顶层四键形状零改动。
     *  与 {@link #seckillActive} 语义同源（同一 resolver 同一采样点）但出口不同：本键服务于
     *  秒杀卡列表（listSeckill 不做 DishServiceImpl 侧装饰，收录口径照 F-112 契约 §1.3 零改动）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(exist = false)
    private Boolean active;
}
