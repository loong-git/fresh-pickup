package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.DepositFlow;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantDeposit;
import com.fresh.mapper.DepositFlowMapper;
import com.fresh.mapper.MerchantDepositMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.service.MerchantDepositService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商家保证金服务实现（P10-5 轨道 B D7/D8）：写路径收口见接口注释；金额一律 DECIMAL(10,2) 定形
 * （setScale(2) 无舍入，照 PartnerWalletServiceImpl 同款口径）。
 * <p><b>代扣与既有账期事务的关系</b>：{@link #deductOnSettlement} 由 AdminServiceImpl#generateSettlement
 * （@Transactional）在既有事务<b>尾部</b>调用，REQUIRED 传播并入主事务（照 PartnerWalletServiceImpl
 * #recordForSettlement ⑦.5 先例）——不新增第二事务；本类任何异常上抛即账单与挂锚整体回滚。
 * <p><b>无档位商家零变化</b>（契约红线）：无 merchant_deposit 行 / required_amount NULL 或 ≤0
 * 一律在首段短路返回 deduct=0，不触碰 settlement 与 deposit_flow——对既有生成账期行为逐字无影响。
 */
@Service
public class MerchantDepositServiceImpl implements MerchantDepositService {

    /** deposit_flow 方向：应结款代扣（06 册 §4.6 枚举原值；模拟轨唯一落值） */
    static final int FLOW_TYPE_SETTLE_DEDUCT = 2;

    /** 模拟通道标注（deposit_flow.channel 恒值；契约红线：资金流水通道字段一律显式 MOCK/SIM） */
    static final String CHANNEL_MOCK = "MOCK";

    /** 跑批代扣的操作主体留痕（deposit_flow.operator；人工动作才落 admin:<key摘要> 形态） */
    static final String OPERATOR_SYSTEM = "SYSTEM";

    /** 账户状态三运行期值（06 册 §4.6 枚举；forfeited/closed 留真实轨不落——登记） */
    static final String STATUS_NONE = "none";
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_ARREARS = "arrears";

    /** dish.category 11 类（init.sql dish.category 列注释原清单；setRequired 的 category 白名单） */
    static final Set<String> CATEGORY_KEYS = Set.of(
            "meat", "vegetable", "fastfood", "wine", "fruit", "fresh",
            "frozen", "snack", "dairy", "grain", "general");

    /** 400 文案（照 AdminServiceImpl#generateSettlement 的「商家不存在」与提现的形状闸门同串） */
    private static final String REQUIRED_SHAPE_MSG = "档位应缴额最多两位小数";

    @Autowired
    private MerchantDepositMapper merchantDepositMapper;
    @Autowired
    private DepositFlowMapper depositFlowMapper;
    @Autowired
    private MerchantMapper merchantMapper;

    // ---------------------------------------------------------------
    // D8 代扣（生成账期事务尾部追加步；REQUIRED 并入主事务——照 recordForSettlement ⑦.5 先例）
    // ---------------------------------------------------------------

    @Override
    @Transactional // REQUIRED：随生成账期主事务执行；本类异常上抛即账单整体回滚（同 ⑦.5 原子一体）
    public Map<String, Object> deductOnSettlement(Long merchantId, String settleNo, BigDecimal net) {
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal required = BigDecimal.ZERO.setScale(2);
        BigDecimal paid = BigDecimal.ZERO.setScale(2);
        String status = STATUS_NONE;
        String category = null;

        // ① 无档位短路（契约红线：无档位商家全跳过，既有行为零变化）——账户缺失/required NULL/≤0
        MerchantDeposit account = merchantId == null ? null
                : merchantDepositMapper.selectOne(new LambdaQueryWrapper<MerchantDeposit>()
                        .eq(MerchantDeposit::getMerchantId, merchantId));
        if (account != null) {
            if (account.getRequiredAmount() != null) {
                required = account.getRequiredAmount();
            }
            if (account.getPaidAmount() != null) {
                paid = account.getPaidAmount();
            }
            status = account.getStatus();
            category = account.getCategory();
        }
        BigDecimal gap = required.subtract(paid);
        if (required.signum() <= 0 || gap.signum() <= 0
                || net == null || net.signum() <= 0) {
            // 未启用/已缴足/无可扣应结：返回现状，settlement 与 deposit_flow 零触碰
            out.put("deduct", BigDecimal.ZERO.setScale(2));
            out.put("paidAfter", paid);
            out.put("status", status);
            out.put("required", required);
            out.put("gap", gap.max(BigDecimal.ZERO));
            return out;
        }

        // ② D8 定额：deduct = min(gap, payable net)；代扣后 payable=net−deduct（回填由调用方承接）
        BigDecimal deduct = gap.min(net).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paidAfter = paid.add(deduct);
        String statusAfter = paidAfter.compareTo(required) >= 0 ? STATUS_ACTIVE : STATUS_ARREARS;

        // ③ 账户乐观落库（条件 UPDATE 带旧值比对；0 行=并发改写 → 抛异常整单回滚，绝不覆盖他人写入）
        int updated = merchantDepositMapper.applyDeduct(account.getId(), paid, paidAfter, statusAfter);
        if (updated != 1) {
            throw new BizException(R.CODE_SERVER_ERROR, "保证金代扣落库冲突（账户已被并发修改），请重试");
        }

        // ④ 流水（type=2 应结款代扣；channel 显式 MOCK——红线：资金流水不得无通道标注；
        //    biz_id=settleNo 来源锚，operator=SYSTEM 跑批留痕；biz_type 恒 null——白名单四类是扣罚
        //    语义，type=2 是缴存形态，06 册 §4.6 列注释原口径）
        DepositFlow flow = new DepositFlow();
        flow.setMerchantId(merchantId);
        flow.setType(FLOW_TYPE_SETTLE_DEDUCT);
        flow.setAmount(deduct);
        flow.setBalanceAfter(paidAfter);
        flow.setBizType(null);
        flow.setBizId(settleNo);
        flow.setOperator(OPERATOR_SYSTEM);
        flow.setChannel(CHANNEL_MOCK);
        flow.setRemark("生成账期 " + settleNo + " 应结款代扣缴存保证金（模拟轨 MOCK，无真实资金流）");
        depositFlowMapper.insert(flow);

        out.put("deduct", deduct);
        out.put("paidAfter", paidAfter);
        out.put("status", statusAfter);
        out.put("required", required);
        out.put("gap", gap);
        return out;
    }

    // ---------------------------------------------------------------
    // D7 模拟触发器（运营端设置档位）
    // ---------------------------------------------------------------

    @Override
    public Map<String, Object> setRequired(Long merchantId, BigDecimal requiredAmount, String category) {
        // ① 参数与归属闸门（merchant 存在 404 照生成账期同文案；金额形状照提现同款）
        if (merchantId == null || merchantId <= 0) {
            throw new BizException("merchantId 必填");
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        if (requiredAmount == null) {
            throw new BizException("requiredAmount 必填（0=停用触发式缴存）");
        }
        if (requiredAmount.signum() < 0) {
            throw new BizException("requiredAmount 不能为负数");
        }
        if (requiredAmount.stripTrailingZeros().scale() > 2) {
            throw new BizException(REQUIRED_SHAPE_MSG);
        }
        requiredAmount = requiredAmount.setScale(2, RoundingMode.HALF_UP);
        if (category != null && !category.trim().isEmpty() && !CATEGORY_KEYS.contains(category.trim())) {
            throw new BizException("类目档位不合法（须为 dish.category 11 类之一）");
        }
        String categoryNorm = category == null || category.trim().isEmpty() ? null : category.trim();

        // ② 现状读取（upsert 前置读；首次启用建户，uk_merchant 兜并发）
        MerchantDeposit account = merchantDepositMapper.selectOne(new LambdaQueryWrapper<MerchantDeposit>()
                .eq(MerchantDeposit::getMerchantId, merchantId));
        BigDecimal paid = account == null || account.getPaidAmount() == null
                ? BigDecimal.ZERO.setScale(2) : account.getPaidAmount();

        MerchantDeposit row = account == null ? new MerchantDeposit() : account;
        row.setMerchantId(merchantId);
        row.setCategory(categoryNorm);
        if (requiredAmount.signum() == 0) {
            // 停用：required 置 0（D8 代扣判断 required>0 短路即跳过），保留已缴记录，状态回 none
            row.setRequiredAmount(BigDecimal.ZERO.setScale(2));
            row.setStatus(STATUS_NONE);
        } else {
            // 启用/调档：required 新值，status 按已缴 vs 新 required 落 active/arrears
            row.setRequiredAmount(requiredAmount);
            row.setStatus(paid.compareTo(requiredAmount) >= 0 ? STATUS_ACTIVE : STATUS_ARREARS);
        }
        if (account == null) {
            row.setDepositAmount(BigDecimal.ZERO.setScale(2));
            row.setRetainedAmount(BigDecimal.ZERO.setScale(2));
            row.setPaidAmount(paid);
            try {
                merchantDepositMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // uk_merchant 并发兜底（照 generateSettlement 的 DuplicateKey 同款姿势）：转为并发冲突语义
                throw new BizException(R.CODE_SERVER_ERROR, "保证金账户已被并发创建，请重试");
            }
        } else {
            merchantDepositMapper.updateById(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchantId);
        out.put("merchantName", merchant.getName());
        out.put("category", categoryNorm);
        out.put("requiredAmount", row.getRequiredAmount());
        out.put("paidAmount", paid);
        out.put("status", row.getStatus());
        return out;
    }

    // ---------------------------------------------------------------
    // 运营端账户列表（D7 管理区只读）
    // ---------------------------------------------------------------

    @Override
    public Map<String, Object> accountOf(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        MerchantDeposit row = merchantDepositMapper.selectOne(new LambdaQueryWrapper<MerchantDeposit>()
                .eq(MerchantDeposit::getMerchantId, merchantId));
        if (row == null) {
            return null;
        }
        Map<String, Object> line = accountLine(row);
        Merchant merchant = merchantMapper.selectById(merchantId);
        line.put("merchantName", merchant == null ? null : merchant.getName());
        return line;
    }

    @Override
    public List<Map<String, Object>> listAccounts() {
        List<MerchantDeposit> rows = merchantDepositMapper.selectList(
                new LambdaQueryWrapper<MerchantDeposit>().orderByAsc(MerchantDeposit::getId));
        // 商家名映射（一次批量，无 N+1，照 listSettlements 同款）
        Map<Long, String> merchantNames = new LinkedHashMap<>();
        Set<Long> merchantIds = new LinkedHashSet<>();
        for (MerchantDeposit row : rows) {
            merchantIds.add(row.getMerchantId());
        }
        if (!merchantIds.isEmpty()) {
            for (Merchant m : merchantMapper.selectBatchIds(merchantIds)) {
                merchantNames.put(m.getId(), m.getName());
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (MerchantDeposit row : rows) {
            Map<String, Object> line = accountLine(row);
            line.put("merchantName", merchantNames.get(row.getMerchantId()));
            list.add(line);
        }
        return list;
    }

    /** 账户行键序（accountOf/listAccounts 共用；merchantName 由调用方按批量映射回填） */
    private static Map<String, Object> accountLine(MerchantDeposit row) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("merchantId", row.getMerchantId());
        line.put("merchantName", null);
        line.put("category", row.getCategory());
        line.put("requiredAmount", row.getRequiredAmount());
        line.put("paidAmount", row.getPaidAmount());
        line.put("status", row.getStatus());
        line.put("updatedAt", row.getUpdateTime() == null ? null : row.getUpdateTime().toString());
        return line;
    }
}
