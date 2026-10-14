package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.dto.PartnerStoreUpdateDTO;
import com.fresh.entity.Store;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.ArrivalItemMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.OpsLogWriter;
import com.fresh.service.PartnerStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主理人门店维护 + 到货批次实现（F-18.12 第 4~6 条）。
 *
 * <p><b>D-23 是本类的存在理由</b>：门店六新列在 {@link Store} 上逐个挂了 {@code @JsonIgnore}
 * （因为 StoreServiceImpl#listAll 把实体本身丢给了匿名的 {@code GET /api/stores}），
 * 所以主理人侧要读它们，只能靠下面 {@link #toOwnerView} 这份<b>显式白名单 Map 重建</b>——
 * 键集合写死在代码里，加一列不会自动多出一条泄露面，少一列也不会静默改协议。
 *
 * <p><b>归属与选择器（RF/D-22）</b>：归属集合只来自 {@code ATTR_PARTNER_STORE_IDS}（拦截器按 token userId 查
 * {@code store.owner_user_id} 注入）；{@code storeId} 只是「在名下多店里选一家」的选择器，
 * 用之前必须先证它 ∈ 集合（不在 → 404），且它<b>永远不进 SET 列</b>。
 * 读写两条路径的 WHERE 都是 {@code id = 选中的店 AND id IN (归属集合)}——
 * 「判定写在 SQL 里」而不是「读出来在内存里比」（同 MerchantDishServiceImpl 的 R3' 姿势）。
 *
 * <p><b>白名单更新（D-7）</b>：形态照 MerchantDishServiceImpl#update——
 * 逐字段 {@code if (非 null) wrapper.set(...)}，白名单之外的键在 DTO 的
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 上就被丢掉，「不报错也不生效」是契约点名的语义。
 * <b>清空</b>一律走 {@code setSql("列 = NULL")}：MP 的 {@code set(col, null)} 会绑一个无 jdbcType 的 null 参数
 * （F-17.2 #4 实测过的 JdbcType OTHER 报错形态），而定长字面量 NULL 不经参数绑定，零风险零注入面。
 *
 * <p><b>photos 的写入路径</b>：非空数组走 patch 实体（{@code photos} 挂了 JacksonTypeHandler，
 * 只有实体路径才带得上列元信息——照 MerchantDishServiceImpl#update 对 tags 的同一处理）；
 * 空数组走 setSql 清空。
 */
@Slf4j
@Service
public class PartnerStoreServiceImpl implements PartnerStoreService {

    /** 门店联系电话形态：与申请侧同一条规则（StoreApplyServiceImpl#PHONE_PATTERN），两处都是各自端点的入参契约 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** date 形状闸门（照 {@code PartnerOrderServiceImpl#DATE_PATTERN} 的写法），形状过了再交 LocalDate 裁决真日期 */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** accepting 取值域（m18 列是 TINYINT NOT NULL DEFAULT 1；0=临时满容/休市，与 status 的 open/closed 正交） */
    private static final Set<Integer> ACCEPTING_VALUES = Set.of(0, 1);

    /** 批次状态：已确认（幂等判定值）与可确认的两态（四条件 UPDATE 的 IN 白名单） */
    private static final String BATCH_CONFIRMED = "confirmed";
    private static final String BATCH_SHORT = "short";

    /** 400 文案复用 GlobalExceptionHandler:55 的既有句子（R2 码表文案零改动纪律） */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    /** 404 文案：他店与不存在不可区分，不给枚举者「这行存在」的信号 */
    private static final String STORE_NOT_FOUND_MSG = "门店不存在";
    private static final String BATCH_NOT_FOUND_MSG = "批次不存在";

    /** ops_log detail 序列化用（Jackson ObjectMapper 线程安全，静态复用同 AdminServiceImpl:64 姿势） */
    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();

    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private ArrivalBatchMapper arrivalBatchMapper;
    @Autowired
    private ArrivalItemMapper arrivalItemMapper;
    @Autowired
    private OpsLogWriter opsLogWriter;

    // ---------------------------------------------------------------
    // F-18.12 第 4 条：GET /api/partner/store
    // ---------------------------------------------------------------

    @Override
    public Map<String, Object> storeDetail(List<Long> storeIds, Long storeIdArg) {
        Store store = findOwnedStore(resolveStoreId(storeIds, storeIdArg), storeIds);
        if (store == null) {
            // 选择器已证它在归属集合内，读不到只剩「行被并发删掉」一种解释 → 与「他店」同码同文案
            throw new BizException(R.CODE_NOT_FOUND, STORE_NOT_FOUND_MSG);
        }
        return toOwnerView(store);
    }

    // ---------------------------------------------------------------
    // F-18.12 第 5 条：PUT /api/partner/store（D-7 七项白名单）
    // ---------------------------------------------------------------

    @Override
    @Transactional
    public Map<String, Object> updateStore(Long userId, List<Long> storeIds, Long queryStoreId,
                                           PartnerStoreUpdateDTO dto) {
        assertStoreIds(storeIds);
        if (userId == null) {
            // ops_log.operator_user_id 是 NOT NULL，绝不能写成「无主审计」，也不能因它落 500
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        // D-22：?storeId= 优先，其次 body 里的 storeId（两者都只是选择器，不是可改列）
        Long selector = queryStoreId != null ? queryStoreId : (dto == null ? null : dto.getStoreId());
        Long storeId = resolveStoreId(storeIds, selector);

        // 值形态与取值域：openTime/closeTime/photos 的注解已拦过形状，这里补 accepting 与 contactPhone
        Integer accepting = dto == null ? null : dto.getAccepting();
        if (accepting != null && !ACCEPTING_VALUES.contains(accepting)) {
            throw new BizException(R.CODE_BAD_REQUEST, "营业开关 accepting 仅接受 0 或 1");
        }
        String contactPhone = trimToNull(dto == null ? null : dto.getContactPhone());
        boolean clearContactPhone = dto != null && isProvidedButBlank(dto.getContactPhone());
        if (contactPhone != null && !PHONE_PATTERN.matcher(contactPhone).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, "联系电话需为 11 位大陆手机号");
        }

        // 审计取旧值所需的读也带归属条件（不是授权判定本身，判定在下面那条 UPDATE 的 WHERE）
        Store before = findOwnedStore(storeId, storeIds);
        if (before == null) {
            throw new BizException(R.CODE_NOT_FOUND, STORE_NOT_FOUND_MSG);
        }

        LambdaUpdateWrapper<Store> wrapper = new LambdaUpdateWrapper<Store>()
                .eq(Store::getId, storeId)
                .in(Store::getId, storeIds);            // RF：归属条件与主键同条 SQL，摘不掉
        List<String> changed = new ArrayList<>();
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        Store patch = null;

        // ① openTime / ② closeTime：null=不改；空串=清空（HH:MM 形状由 DTO 的 @Pattern 保证，最长 5 字符 = 列宽）
        String openTime = dto == null ? null : dto.getOpenTime();
        if (openTime != null) {
            String value = trimToNull(openTime);
            applyTextChange(wrapper, changed, oldValues, newValues,
                    "openTime", Store::getOpenTime, "open_time", before.getOpenTime(), value);
        }
        String closeTime = dto == null ? null : dto.getCloseTime();
        if (closeTime != null) {
            String value = trimToNull(closeTime);
            applyTextChange(wrapper, changed, oldValues, newValues,
                    "closeTime", Store::getCloseTime, "close_time", before.getCloseTime(), value);
        }

        // ③ dailyCapacity（D-8：一期只展示，这里不做「满容自动降级 accepting」）
        Integer dailyCapacity = dto == null ? null : dto.getDailyCapacity();
        if (dailyCapacity != null && !dailyCapacity.equals(before.getDailyCapacity())) {
            wrapper.set(Store::getDailyCapacity, dailyCapacity);
            changed.add("dailyCapacity");
            oldValues.put("dailyCapacity", before.getDailyCapacity());
            newValues.put("dailyCapacity", dailyCapacity);
        }

        // ④ accepting
        if (accepting != null && !accepting.equals(before.getAccepting())) {
            wrapper.set(Store::getAccepting, accepting);
            changed.add("accepting");
            oldValues.put("accepting", before.getAccepting());
            newValues.put("accepting", accepting);
        }

        // ⑤ contactPhone（空串=清空；detail 里两侧都只出脱敏值，RD）
        if (contactPhone != null) {
            if (!contactPhone.equals(before.getContactPhone())) {
                wrapper.set(Store::getContactPhone, contactPhone);
                changed.add("contactPhone");
                oldValues.put("maskedContactPhone", PhoneMask.mask(before.getContactPhone()));
                newValues.put("maskedContactPhone", PhoneMask.mask(contactPhone));
            }
        } else if (clearContactPhone && before.getContactPhone() != null) {
            // 已经是 NULL 时不再写一次 NULL：ops_log 记的是「改了什么」，无变化就不该多一行
            wrapper.setSql("contact_phone = NULL");
            changed.add("contactPhone");
            oldValues.put("maskedContactPhone", PhoneMask.mask(before.getContactPhone()));
            newValues.put("maskedContactPhone", null);
        }

        // ⑥ service
        String service = dto == null ? null : dto.getService();
        if (service != null) {
            String value = trimToNull(service);
            applyTextChange(wrapper, changed, oldValues, newValues,
                    "service", Store::getService, "service", before.getService(), value);
        }

        // ⑦ photos：非空数组走 patch 实体（JacksonTypeHandler 只在实体路径生效），空数组走 setSql 清空
        List<String> photos = dto == null ? null : dto.getPhotos();
        if (photos != null) {
            boolean toNull = photos.isEmpty();
            if (!toNull || before.getPhotos() != null) {
                if (toNull) {
                    wrapper.setSql("photos = NULL");
                } else {
                    patch = new Store();
                    patch.setPhotos(photos);
                }
                changed.add("photos");
                oldValues.put("photosCount", before.getPhotos() == null ? 0 : before.getPhotos().size());
                newValues.put("photosCount", toNull ? 0 : photos.size());
            }
        }

        if (changed.isEmpty()) {
            // 白名单七项一个都没传（或传了但值与库里相同）：不 UPDATE、不写 ops_log，仍回当前视图。
            // 契约点的是「其余键一律忽略（不报错、不生效）」，所以这里连 name/address-only 的 body 也不报错。
            log.info("门店维护无白名单变更: storeId={}, operatorUserId={}", storeId, userId);
            return toOwnerView(before);
        }

        // 影响行数判定 = 授权判定本身（同 MerchantDishServiceImpl:249 的姿势）
        if (storeMapper.update(patch, wrapper) == 0) {
            throw new BizException(R.CODE_NOT_FOUND, STORE_NOT_FOUND_MSG);
        }

        // ops_log：现场动作（D-5「门店信息变更」明确归 ops_log），detail 只放变更字段名 + 新旧安全摘要
        opsLogWriter.write(userId, "update_store", String.valueOf(storeId), buildDetail(changed, oldValues, newValues));
        log.info("门店维护成功: storeId={}, operatorUserId={}, changed={}", storeId, userId, changed);

        // 回查而非拼装「before + 本次入参」：出参必须是落库后的权威值（同 adjustStock 返回 DB 真实库存的理由）
        Store after = findOwnedStore(storeId, storeIds);
        if (after == null) {
            throw new BizException(R.CODE_NOT_FOUND, STORE_NOT_FOUND_MSG);
        }
        return toOwnerView(after);
    }

    // ---------------------------------------------------------------
    // F-18.12 第 6 条：到货批次读 + 确认
    // ---------------------------------------------------------------

    @Override
    public Map<String, Object> arrivals(List<Long> storeIds, Long storeIdArg, String date) {
        Long storeId = resolveStoreId(storeIds, storeIdArg);
        // 缺省=明日（D-19/m18 演示种子那一行的日期），让「当日未确认」警示条在验收现场演示得出来
        LocalDate effectiveDate = date == null || date.trim().isEmpty()
                ? LocalDate.now().plusDays(1)
                : parseDate(date.trim());

        List<Map<String, Object>> batches = arrivalBatchMapper.selectBatchesByStoreAndDate(storeId, effectiveDate);

        Map<Long, List<Map<String, Object>>> itemsByBatch = new LinkedHashMap<>();
        if (!batches.isEmpty()) {
            List<Long> batchIds = new ArrayList<>(batches.size());
            for (Map<String, Object> batch : batches) {
                Long id = toLong(batch.get("id"));
                if (id != null) {
                    batchIds.add(id);
                }
            }
            if (!batchIds.isEmpty()) {
                // 一次批量取明细再分组，不在循环里查库（N+1）
                for (Map<String, Object> item : arrivalItemMapper.selectItemsByBatchIds(batchIds)) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", item.get("id"));
                    line.put("batchId", item.get("batchId"));
                    line.put("dishId", item.get("dishId"));
                    line.put("dishName", item.get("dishName"));
                    line.put("expectedQty", toInteger(item.get("expectedQty")));
                    line.put("actualQty", toInteger(item.get("actualQty")));
                    itemsByBatch.computeIfAbsent(toLong(item.get("batchId")), k -> new ArrayList<>()).add(line);
                }
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(batches.size());
        for (Map<String, Object> batch : batches) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", batch.get("id"));
            line.put("storeId", batch.get("storeId"));
            line.put("batchDate", batch.get("batchDate"));
            line.put("status", batch.get("status"));
            // D-9：confirmed 是给前端出警示条用的那一位（status='confirmed' 才算已确认）
            line.put("confirmed", BATCH_CONFIRMED.equals(batch.get("status")));
            line.put("confirmTime", batch.get("confirmTime"));
            line.put("remark", batch.get("remark"));
            List<Map<String, Object>> items = itemsByBatch.get(toLong(batch.get("id")));
            line.put("items", items == null ? List.of() : items);
            list.add(line);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", storeId);
        data.put("date", effectiveDate.toString());
        data.put("list", list);
        return data;
    }

    // 隔离级别显式降为 READ_COMMITTED（同 PartnerOrderServiceImpl#verify）：并发输家的「UPDATE 回 0 行后再读定性」（BE-4）
    // 必须看到赢家已提交的 confirmed，而 MySQL 默认 REPEATABLE READ 会把这次再读锁在事务快照上；条件 UPDATE 仍用当前读。
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> confirmArrival(Long userId, List<Long> storeIds, Long batchId) {
        assertStoreIds(storeIds);
        if (userId == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        if (batchId == null) {
            throw new BizException(R.CODE_NOT_FOUND, BATCH_NOT_FOUND_MSG);
        }

        // 定性预读（照 PartnerOrderServiceImpl#verify 的两步姿势：先定性再条件 UPDATE，
        // 「已是目标态」必须走幂等成功而不是靠 UPDATE 的行数猜）。
        // BE-12：预读 SQL 已带 store_id IN (归属集合)（判定在 SQL 里），下面的内存比集合保留作纵深（双保险不互替）。
        Map<String, Object> row = arrivalBatchMapper.selectForConfirm(batchId, storeIds);
        if (row == null || !storeIds.contains(toLong(row.get("storeId")))) {
            // 他店批次与不存在的批次同码同文案：不泄漏「这个 id 真实存在」
            throw new BizException(R.CODE_NOT_FOUND, BATCH_NOT_FOUND_MSG);
        }
        String status = row.get("status") == null ? "" : String.valueOf(row.get("status"));
        if (BATCH_CONFIRMED.equals(status)) {
            log.info("到货确认幂等命中: batchId={}, operatorUserId={}", batchId, userId);
            return confirmResult(batchId, row.get("confirmTime"), true);
        }
        if (BATCH_SHORT.equals(status)) {
            // 短少批次不该被「确认到货」抹平（一期短少只在商家侧 fulfill_status 那条链登记，D-9）
            throw new BizException(R.CODE_BAD_REQUEST, "该批次已登记短少，不可直接确认");
        }

        int updated = arrivalBatchMapper.confirmById(batchId, storeIds);
        if (updated == 0) {
            // BE-4（F-18.20 D 行，与 verify 同形）：预读说该翻、UPDATE 却回 0 行 = 并发窗口内已被他人确认或状态被改。
            // 再按 id + 归属集合读一次定性：已是 confirmed → 幂等成功（回 confirmTime，不写 ops_log）；
            // 其余（short/读不到=被并发改到集合外或删行）维持 404 定性。
            Map<String, Object> reread = arrivalBatchMapper.selectForConfirm(batchId, storeIds);
            if (reread != null && BATCH_CONFIRMED.equals(String.valueOf(reread.get("status")))) {
                log.info("到货确认幂等命中（并发抢先）: batchId={}, operatorUserId={}", batchId, userId);
                return confirmResult(batchId, reread.get("confirmTime"), true);
            }
            log.warn("到货确认状态已变化: batchId={}, operatorUserId={}, readStatus={}", batchId, userId, status);
            throw new BizException(R.CODE_NOT_FOUND, BATCH_NOT_FOUND_MSG);
        }

        opsLogWriter.write(userId, "confirm_arrival", String.valueOf(batchId),
                "{\"from\":\"" + status + "\",\"to\":\"" + BATCH_CONFIRMED
                        + "\",\"storeId\":" + row.get("storeId") + "}");
        log.info("到货确认成功: batchId={}, storeId={}, operatorUserId={}", batchId, row.get("storeId"), userId);
        Map<String, Object> fresh = arrivalBatchMapper.selectForConfirm(batchId, storeIds);
        return confirmResult(batchId, fresh == null ? null : fresh.get("confirmTime"), false);
    }

    // ---------------------------------------------------------------
    // 支撑方法
    // ---------------------------------------------------------------

    /**
     * D-22 门店选择器：{@code null} → 取归属集合里 id 最小的一家（= 最早开的店，演示库 user 1 只有 store 1，走这里）；
     * 非 null 且不在集合内 → 404。
     * <p>它<b>只是选择器</b>：归属始终由服务端那份集合界定（RF「不收归属入参」指的是归属，不是「不许在名下选一家」），
     * 也不允许把集合外的 id 传进后续任何 SQL——下面的 findOwnedStore/UPDATE 仍各自带 {@code IN (归属集合)}。
     * <p>取「id 最小」而非「集合第一个」：拦截器那条 selectList 没有 ORDER BY，
     * 「第一个」在不同执行计划下不稳定，两家店的同名请求会在两个店之间跳。
     */
    private Long resolveStoreId(List<Long> storeIds, Long selector) {
        assertStoreIds(storeIds);
        if (selector == null) {
            return Collections.min(storeIds);
        }
        if (!storeIds.contains(selector)) {
            throw new BizException(R.CODE_NOT_FOUND, STORE_NOT_FOUND_MSG);
        }
        return selector;
    }

    /** 归属集合自守卫（纵深防御，同 PartnerOrderServiceImpl#assertStoreIds：空集合绝不退化成无条件全表读） */
    private void assertStoreIds(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
    }

    /**
     * 单店读的唯一封装：{@code WHERE id = ? AND id IN (归属集合)}，
     * 且只取主理人视图需要的十三列——<b>owner_user_id / created_at 连 select 都没取</b>（D-12 同口径）。
     */
    private Store findOwnedStore(Long storeId, List<Long> storeIds) {
        assertStoreIds(storeIds);
        return storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .select(Store::getId, Store::getName, Store::getAddress, Store::getService, Store::getStatus,
                        Store::getLng, Store::getLat, Store::getOpenTime, Store::getCloseTime,
                        Store::getDailyCapacity, Store::getAccepting, Store::getContactPhone, Store::getPhotos)
                .eq(Store::getId, storeId)
                .in(Store::getId, storeIds));
    }

    /**
     * D-23 白名单出参重建（本类唯一允许把 Store 变成响应体的地方）：
     * 键集合写死，{@code contactPhone} 只出 {@code PhoneMask.mask} 结果（RD：出参不出现明文手机号），
     * {@code ownerUserId}/{@code createdAt} 不给出口。
     */
    private Map<String, Object> toOwnerView(Store store) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", store.getId());
        view.put("name", store.getName());
        view.put("address", store.getAddress());
        view.put("service", store.getService());
        view.put("status", store.getStatus());
        view.put("lng", store.getLng());
        view.put("lat", store.getLat());
        view.put("openTime", store.getOpenTime());
        view.put("closeTime", store.getCloseTime());
        view.put("dailyCapacity", store.getDailyCapacity());
        view.put("accepting", store.getAccepting());
        view.put("contactPhone", PhoneMask.mask(store.getContactPhone()));
        view.put("photos", store.getPhotos());
        return view;
    }

    /**
     * 文本列的「改值 / 清空」两态处理：
     * {@code value=null} 表示清空 → {@code setSql("列 = NULL")}（定长字面量，不经参数绑定，
     * 避开 MP 的 {@code set(col, null)} 绑无 jdbcType null 参数那个坑，见类注释）；
     * {@code value} 非 null → 走 {@code wrapper.set(columnRef, value)} 的正常参数绑定路径。
     * 同值/已空值都不记账（changed 里不出现），于是「传了但没改」不会产生 ops_log 行。
     */
    private void applyTextChange(LambdaUpdateWrapper<Store> wrapper, List<String> changed,
                                 Map<String, Object> oldValues, Map<String, Object> newValues,
                                 String key, SFunction<Store, ?> columnRef, String column,
                                 String oldValue, String value) {
        if (value == null) {
            if (oldValue == null) {
                return;
            }
            wrapper.setSql(column + " = NULL");
        } else {
            if (value.equals(oldValue)) {
                return;
            }
            wrapper.set(columnRef, value);
        }
        changed.add(key);
        oldValues.put(key, oldValue);
        newValues.put(key, value);
    }

    /** ops_log detail：变更字段名列表 + 新旧安全摘要（手机号已脱敏、照片只记张数）；超长由 OpsLogWriter 兜底 */
    private String buildDetail(List<String> changed, Map<String, Object> oldValues, Map<String, Object> newValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fields", changed);
        detail.put("old", oldValues);
        detail.put("new", newValues);
        try {
            return DETAIL_MAPPER.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 值全是受限类型（字符串/数字/null），走不到；兜底也不给明文手机号留口子
            return "{\"fields\":" + changed + "}";
        }
    }

    private Map<String, Object> confirmResult(Long batchId, Object confirmTime, boolean idempotent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", batchId);
        data.put("status", BATCH_CONFIRMED);
        data.put("confirmed", true);
        data.put("confirmTime", confirmTime);
        data.put("idempotent", idempotent);
        return data;
    }

    /** date 解析照 PartnerOrderServiceImpl#parseDate：形状不过或真日期不过都 400，同一条文案 */
    private LocalDate parseDate(String raw) {
        if (!DATE_PATTERN.matcher(raw).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeException e) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 「传了这个键但值是空串/纯空白」= 要把该列清空的指令（与「没传这个键」= null 严格区分） */
    private boolean isProvidedButBlank(String value) {
        return value != null && value.trim().isEmpty();
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 驱动/聚合类型归一（照 PartnerOrderServiceImpl#toInteger）：INT 列可能回 Long/BigDecimal */
    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return null;
    }
}
