package com.fresh.service;

import java.util.List;
import java.util.Map;

public interface DictService {

    /**
     * 字典分组词条（F-108 字典本地化后，契约 §0.3）：
     * <ol>
     *   <li>组 {@code enabled=0} → 返回空列表，不回退上游；</li>
     *   <li>本地 {@code dict_item} 有词条 → 返回本地（Caffeine 10 分钟缓存），不调上游；</li>
     *   <li>本地空且组 {@code enabled=1} → 回退上游（T-M2-07 原逻辑，上游已降级为同步源）。</li>
     * </ol>
     * 出参形状与改造前逐字一致：{@code [{word, definition, sortOrder}]}，按 sortOrder 降序。
     *
     * @param group 分组代码：dish_category / coupon_template / kingkong_entry / service_tags
     * @return 词条列表 [{word, definition, sortOrder}]，按 sortOrder 降序（与前端 normalizeList 结构一致）
     */
    List<Map<String, Object>> getGroup(String group);

    /**
     * 从上游同步某分组词条（F-108 §0.8，用户 2026-10-23 选定 B 语义「只增不删」）：
     * <ol>
     *   <li>组不存在 → 400「字典分组不存在」；组 {@code enabled=0} → 400「分组已停用，不同步」；</li>
     *   <li>调既有 {@code fetchFromUpstream} 拉上游；<b>失败原样抛它的 BizException</b>
     *       （400「字典服务暂时不可用，请稍后重试」），绝不静默当成功；</li>
     *   <li>成功时逐条 {@code INSERT IGNORE}（{@code uk_group_word} 判重）：本地没有的插入计入
     *       {@code inserted}，本地已有的（含运营改过的）一行不动计入 {@code skipped}；</li>
     *   <li>有插入才清该组 Caffeine；返回 {@code {upstreamTotal, inserted, skipped}}。</li>
     * </ol>
     * 本方法<b>不删除、不更新</b>任何既有行；上游改名的表现是「新名插入、旧名保留」（契约写明的代价）。
     *
     * @param group 分组代码（dict_group.code）
     * @return 定序 {@code LinkedHashMap}：upstreamTotal / inserted / skipped
     * @throws com.fresh.common.BizException 组不存在、组已停用、分组无上游映射或上游拉取失败（均 code=400）
     */
    Map<String, Object> syncFromUpstream(String group);

    /**
     * 清除某分组的 Caffeine 缓存（F-108 §0.4 运营 evict 端点）。
     * <p>只清缓存、不从上游强制重拉（上游当前不可用，重拉只会造 400）；清完后下一次
     * {@link #getGroup(String)} 会重新读本地 dict_item。
     *
     * @param group 分组代码
     * @throws com.fresh.common.BizException 分组在 dict_group 里不存在（对外 code=400）
     */
    void evict(String group);
}
