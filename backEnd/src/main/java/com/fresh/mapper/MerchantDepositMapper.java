package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.MerchantDeposit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 商家保证金账户 mapper（P10-5 D7/D8，m33 建表）：BaseMapper 承担 SELECT（代扣前置读/运营列表/商家自见）
 * 与 INSERT（setRequired 首次启用建户）；唯一 UPDATE 方法是 {@link #applyDeduct}（D8 代扣的条件 UPDATE），
 * DELETE 为零。setRequired 的档位改写走 BaseMapper#updateById（全字段实体更新，非本类手写 SQL）。
 */
@Mapper
public interface MerchantDepositMapper extends BaseMapper<MerchantDeposit> {

    /**
     * D8 代扣落库（唯一手写 UPDATE）：已缴额从 paidBefore 乐观置为 paidAfter（WHERE 带旧值比对——
     * 若读取与落库之间被并发改写，影响行数 0，调用方抛异常整单回滚，绝不覆盖他人写入），
     * status 一并由调用方按「paidAfter 与 required 比较」算好传入（不在 SQL 里比大小——
     * MySQL UPDATE 的 SET 列表自左向右求值，引用本语句内被赋值列的行为有歧义，不在 SQL 层赌它）。
     * 影响行数 1=落库成功。
     */
    @Update("UPDATE merchant_deposit SET paid_amount = #{paidAfter}, status = #{status} "
            + "WHERE id = #{id} AND paid_amount = #{paidBefore}")
    int applyDeduct(@Param("id") Long id,
                    @Param("paidBefore") BigDecimal paidBefore,
                    @Param("paidAfter") BigDecimal paidAfter,
                    @Param("status") String status);
}
