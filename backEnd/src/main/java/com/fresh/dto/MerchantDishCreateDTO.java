package com.fresh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商家端新增商品入参白名单（F-13/G-02 Step 1，契约 F-13.2 表行 3）。
 * R5' 硬约束：seckill / seckillPrice / soldCount / goodRate / merchantId / id 六个字段在本 DTO 中不存在——
 * 秒杀列二期才开放（M-08）、sold_count 由支付链路累加、good_rate 由评价聚合回写（DishMapper#refreshGoodRate）、
 * 归属 merchant_id 与主键 id 一律服务端解析（@RequestAttribute），前端传什么都进不来。
 * category 不在此硬编码枚举：取值域由字典 11 类约束（init.sql:67 列注释 meat/vegetable/... 共 11 code），
 * 商家端只能从字典里选（F-13.4 前端契约「11 类只选不建」），服务端不复制一份枚举以免两处漂移。
 */
@Data
public class MerchantDishCreateDTO {

    /** 商品名（dish.name VARCHAR(100) NOT NULL） */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称不能超过 100 个字")
    private String name;

    /** 单价（dish.price DECIMAL(10,2)：整数位 8 位 + 2 位小数，列宽即校验上限） */
    @NotNull(message = "请填写商品价格")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "价格最多 8 位整数、2 位小数")
    private BigDecimal price;

    /** 商品图相对路径（dish.image VARCHAR(255) 可空；空值 C 端回退 emoji 占位） */
    @Size(max = 255, message = "图片地址过长")
    private String image;

    /** 详细描述（dish.description 列是 TEXT，上限按应用层口径收到 2000，防超长详情把列表接口拖慢） */
    @Size(max = 2000, message = "描述不能超过 2000 个字")
    private String description;

    /** 分类 code（dish.category VARCHAR(20) NOT NULL，对齐字典 11 类，见类注释） */
    @NotBlank(message = "请选择商品分类")
    @Size(max = 20, message = "分类取值过长")
    private String category;

    /** 计量单位（dish.unit VARCHAR(20) NOT NULL：斤/盒/只/个/份/根/块/棵） */
    @NotBlank(message = "请填写商品单位")
    @Size(max = 20, message = "单位不能超过 20 个字")
    private String unit;

    /**
     * 初始库存。仅用于新建时定初值，不构成「变更库存」路径——
     * R7' 的精确 set 禁令管的是对已有商品的读-改-写，商家侧改库存一律走 PUT /{id}/stock 增量。
     */
    @NotNull(message = "请填写库存数量")
    @Min(value = 0, message = "库存不能为负数")
    @Max(value = 99999, message = "库存不能超过 99999")
    private Integer stock;

    /** 占位 emoji（dish.emoji VARCHAR(50)） */
    @Size(max = 50, message = "表情符号过长")
    private String emoji;

    /** 卡片背景色（dish.bg_color VARCHAR(20)） */
    @Size(max = 20, message = "背景色值过长")
    private String bgColor;

    /**
     * 卖点标签（dish.tags VARCHAR(255) 存 JSON 数组串，经 JacksonTypeHandler 映射）。
     * 上限推导：5 个 × 12 字 + JSON 括号/引号/逗号 ≈ 76 字符，最坏（含被转义字符）也远在列宽内；
     * 元素级约束写在类型参数上（Bean Validation 2.0 容器元素约束，无需额外 @Valid）。
     */
    @Size(max = 5, message = "标签最多 5 个")
    private List<@Size(max = 12, message = "单个标签不能超过 12 个字") String> tags;

    /** 单次限购数量（dish.limit_buy INT NOT NULL DEFAULT 5，下单校验读这一列） */
    @NotNull(message = "请填写限购数量")
    @Min(value = 1, message = "限购数量至少为 1")
    @Max(value = 20, message = "限购数量不能超过 20")
    private Integer limitBuy;
}
