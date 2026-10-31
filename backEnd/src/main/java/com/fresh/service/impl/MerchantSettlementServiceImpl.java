package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Settlement;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.SettlementMapper;
import com.fresh.service.MerchantSettlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端结算单只读服务实现（F-120 §1，P5-2 §1 列表分页化）。
 * <p>口径纪律：本类不发明第二套结算口径——对账三条件与 orphan 语义逐行照运营端
 * AdminServiceImpl#listSettlements / #settlementItems（F-118 定稿 + 2026-10-25 验收裁决的孤儿第三条件），
 * 两端读同一批 SQL（OrderItemMapper#selectAnchoredAggregates / #selectAnchoredItems），
 * 只是出参收成商家自见子集。运营端在本片零改动（F-120 §3 红线），故这里是平行实现而非抽公共方法。
 */
@Service
public class MerchantSettlementServiceImpl implements MerchantSettlementService {

    /**
     * 分页闸门参数与文案（P5-2 §1，逐字照 AdminServiceImpl#listSettlements 同名常量语义：
     * 缺省 1/20、pageNum≥1、1≤pageSize≤100、越界 400 不夹取；400 文案同句照抄，不新造串）。
     */
    private static final int LIST_DEFAULT_PAGE_NUM = 1;
    private static final int LIST_DEFAULT_PAGE_SIZE = 20;
    private static final int LIST_MIN_PAGE_SIZE = 1;
    private static final int LIST_MAX_PAGE_SIZE = 100;
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    @Autowired
    private SettlementMapper settlementMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;

    /**
     * 分页化后的本人账单列表（P5-2 §1，键集/归属口径零改动）：
     * 归属恒等值下沉 SettlementMapper（total 复用 countSettlements——商家侧无筛选维度，账期两参恒 null；
     * 页走 selectSettlementsPageAsc，id 升序是 F-120 §1 刻意差异，与运营端 selectSettlementsPage 的 DESC
     * 刻意不同互不复用）；闸门/offset 用 long 防溢出/offset ≥ total 短路空 list 且 total 仍真值，
     * 逐字照 AdminServiceImpl#listSettlements（F-125）；出参封口 {list,total,pageNum,pageSize}，无 filters 键。
     */
    @Override
    public Map<String, Object> list(Long merchantId, Integer pageNum, Integer pageSize) {
        if (merchantId == null) {
            // 纵深防御：拦截器已保证 /api/merchant/** 进来的 merchantId 非 null（照 MerchantDishServiceImpl.list 首行）
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        int pageNo = pageNum == null ? LIST_DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < LIST_DEFAULT_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        int size = pageSize == null ? LIST_DEFAULT_PAGE_SIZE : pageSize;
        if (size < LIST_MIN_PAGE_SIZE || size > LIST_MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        long total = settlementMapper.countSettlements(merchantId, null, null);
        long offset = (long) (pageNo - 1) * size;
        List<Settlement> rows = offset >= total
                ? List.of()
                : settlementMapper.selectSettlementsPageAsc(merchantId, offset, size);
        // 对账聚合一次批量、只喂当页 settleIds（无账单时短路不下发空 IN，照 AdminServiceImpl#listSettlements 同款短路）
        Map<Long, Map<String, Object>> anchored = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            List<Long> settleIds = new ArrayList<>(rows.size());
            for (Settlement row : rows) {
                settleIds.add(row.getId());
            }
            for (Map<String, Object> agg : orderItemMapper.selectAnchoredAggregates(settleIds)) {
                anchored.put(((Number) agg.get("settleId")).longValue(), agg);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Settlement row : rows) {
            out.add(merchantLine(row, anchored.get(row.getId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", out);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        return data;
    }

    @Override
    public List<Map<String, Object>> items(Long merchantId, Long id) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        // 归属校验（F-120 §1）：不存在 与 他商家账单 同一 404 文案「账单不存在」——
        // 不泄露他人账单存在性（照订单归属校验惯例/MerchantDishServiceImpl 404 同文案口径）。
        // 注意文案与运营端「结算单不存在」刻意不同串：那是运营管理视角的报错，商家侧照契约用「账单」。
        Settlement row = id == null ? null : settlementMapper.selectById(id);
        if (row == null || !merchantId.equals(row.getMerchantId())) {
            throw new BizException(R.CODE_NOT_FOUND, "账单不存在");
        }
        // orphan 语义照运营端口径（AdminServiceImpl#settlementItems）：SQL 侧 (o.id IS NULL) 是 1/0 整数，
        // 归一 Boolean 下发；孤儿行保留、payStatus 显 null，前端标「订单已不存在」警示（不静默丢行）
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : orderItemMapper.selectAnchoredItems(id)) {
            Map<String, Object> line = new LinkedHashMap<>(raw);
            Object orphan = raw.get("orphan");
            line.put("orphan", orphan != null && ((Number) orphan).intValue() != 0);
            out.add(line);
        }
        return out;
    }

    /**
     * 商家自见子集行（F-120 §1 键序即出参序）：netAmount 取 payable（契约「netAmount==payable」），
     * 日期串服务端定形（照运营端 D-18 同一手势）；运营管理字段 merchantId/merchantName/gross/refund/
     * net/retain/itemCount 一律不出。reconcileOk 三条件逐行照 AdminServiceImpl#listSettlements——
     * ① 账面 gross==实时挂锚 Σ(supply_price×qty)；② 账面 order_count==实时去重订单数；③ 孤儿数==0；
     * 无聚合行（锚被清）也 false（宁可错杀不可漏报）。paidChannelAbsent 恒 true（打款通道未接，轨道 B）。
     */
    private Map<String, Object> merchantLine(Settlement row, Map<String, Object> agg) {
        boolean reconcileOk = false;
        if (agg != null) {
            BigDecimal anchoredGross = (BigDecimal) agg.get("gross");
            long anchoredOrders = ((Number) agg.get("orderCount")).longValue();
            long orphanCount = ((Number) agg.get("orphanCount")).longValue();
            reconcileOk = anchoredGross != null
                    && anchoredGross.compareTo(row.getGmvAmount()) == 0
                    && anchoredOrders == row.getOrderCount()
                    && orphanCount == 0;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", row.getId());
        line.put("settleNo", row.getSettleNo());
        line.put("periodStart", row.getPeriodStart() == null ? null : row.getPeriodStart().toString());
        line.put("periodEnd", row.getPeriodEnd() == null ? null : row.getPeriodEnd().toString());
        line.put("gmvAmount", row.getGmvAmount());
        line.put("refundAmount", row.getRefundAmount());
        line.put("netAmount", row.getPayable());
        line.put("orderCount", row.getOrderCount());
        line.put("commission", row.getCommission());
        line.put("status", row.getStatus());
        line.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        line.put("reconcileOk", reconcileOk);
        line.put("paidChannelAbsent", Boolean.TRUE);
        return line;
    }
}
