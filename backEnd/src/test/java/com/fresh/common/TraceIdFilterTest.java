package com.fresh.common;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-42a ①（T-M1-02 DoD③）：traceId 生成与「日志真的带得上」的离线单测。
 * <b>不起 Spring 容器、不连库、不 mock servlet</b>（本仓测试全离线是既有性质，
 * 照 {@code RateLimitRetryAfterTest} 的路子只测静态纯函数 + 读一份配置文件）。
 * 四条断言各自锁一件事：
 * ① 生成函数的长度与字符集稳定（日志要能读，也要求它可被下游脚本按定长切）；
 * ② 同一入口连续调用产出不同值（否则两条并发请求共用一个 traceId，DoD③ 直接失效）；
 * ③ MDC 键名与 logback-spring.xml 的 {@code %X{traceId}} 一字对齐，且两个 appender 都吃到这个 pattern
 *    ——这一条正是原缺口的本体：文档说「traceId 对应」，pattern 里根本没有它；
 * ④ traceId 只进 header 不进 body（{@link R} 的字段仍是 code/data/message 三个，HTTP 恒 200 的信封契约不动）。
 * 活体证据（真实请求的日志行里出现 traceId）由主会话重启后端验，本类不冒充它。
 */
class TraceIdFilterTest {

    /** 生成函数约定的字符集：8 位小写十六进制（UUID 去横线后的前 8 位） */
    private static final Pattern HEX_8 = Pattern.compile("^[0-9a-f]{8}$");

    @Test
    @DisplayName("traceId 长度恒 8、字符集恒 [0-9a-f]：2000 次调用无一例外")
    void 长度与字符集稳定() {
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String traceId = TraceIdFilter.newTraceId();
            assertNotNull(traceId, "生成结果不可为 null");
            assertEquals(TraceIdFilter.TRACE_ID_LENGTH, traceId.length(),
                    "长度必须恒为 " + TraceIdFilter.TRACE_ID_LENGTH + "，实测=" + traceId);
            assertTrue(HEX_8.matcher(traceId).matches(),
                    "字符集必须恒为小写十六进制，实测=" + traceId);
            if (i < 5) {
                samples.add(traceId);
            }
        }
        // 样例打进 mvn 输出：验收②要的「traceId 实际样例值 + 长度断言通过」证据
        System.out.println("[F-42a][traceId 单测样例] 长度=" + TraceIdFilter.TRACE_ID_LENGTH
                + " 字符集=[0-9a-f] 连续 5 个实值=" + String.join(", ", samples)
                + " 响应头=" + TraceIdFilter.TRACE_ID_HEADER + " MDC键=" + TraceIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("同一入口连续调用必产出不同值：256 次全不重复（32 位空间下允许的概率级撞值不算功能失效，见类注释）")
    void 连续调用产出不同值() {
        Set<String> distinct = new HashSet<>();
        String previous = TraceIdFilter.newTraceId();
        distinct.add(previous);
        for (int i = 0; i < 255; i++) {
            String next = TraceIdFilter.newTraceId();
            assertFalse(next.equals(previous), "相邻两次调用不应返回同一个值：前一次=" + previous + " 本次=" + next);
            distinct.add(next);
            previous = next;
        }
        assertEquals(256, distinct.size(), "256 次调用必须得到 256 个不同 traceId，实测去重后=" + distinct.size());
    }

    /**
     * 锁住 DoD③ 的本体：MDC 键名 ↔ logback pattern 的字面对齐 + 两个 appender 都覆盖。
     * 配置文件按 Maven 模块工作目录（backEnd/）寻址；从仓库根或非 Maven 工作目录跑时两种候选路径都试，
     * 都找不到即 assumption 跳过（不冒充绿，也不因 IDE 工作目录不同误红）。
     */
    @Test
    @DisplayName("MDC 键名与 logback pattern 对齐：%X{traceId} 存在且 CONSOLE/FILE 两个 appender 共用该 pattern")
    void pattern真的带上了traceId() throws IOException {
        assertEquals("traceId", TraceIdFilter.MDC_KEY, "MDC 键名一旦改动，logback pattern 必须同步");

        Path file = locateLogbackConfig();
        Assumptions.assumeTrue(file != null, "未找到 logback-spring.xml（工作目录非 backEnd/ 或仓库根），本条跳过");

        String xml = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("name=\"LOG_PATTERN\"[^>]*value=\"([^\"]*)\"").matcher(xml);
        assertTrue(matcher.find(), "LOG_PATTERN 属性必须存在：" + file.toAbsolutePath());
        String pattern = matcher.group(1);
        assertTrue(pattern.contains("%X{" + TraceIdFilter.MDC_KEY + "}"),
                "pattern 必须带 %X{traceId}，否则响应与堆栈对不上号。实测 pattern=" + pattern);
        assertEquals(2, countOccurrences(xml, "<pattern>${LOG_PATTERN}</pattern>"),
                "CONSOLE 与 FILE 两个 appender 都要引用同一 LOG_PATTERN（少一个就是一半日志没 traceId）");
    }

    @Test
    @DisplayName("traceId 不进 body：R 的字段仍只有 code/data/message，HTTP 恒 200 的信封结构未被扩字段")
    void traceId只出现在header不出现在body() {
        Set<String> fields = new HashSet<>();
        for (var field : R.class.getDeclaredFields()) {
            String name = field.getName();
            if (name.startsWith("CODE") || name.startsWith("MSG") || "COMPANION".equals(name)) {
                continue; // 常量不参与序列化
            }
            fields.add(name);
        }
        assertEquals(Set.of("code", "data", "message"), fields, "信封字段集合一旦变化就是改了对外结构，实测=" + fields);
        assertFalse(fields.stream().anyMatch(f -> f.toLowerCase().contains("trace")),
                "traceId 绝不允许进 body（对外唯一出处是响应头 " + TraceIdFilter.TRACE_ID_HEADER + "）");
        assertEquals("X-Trace-Id", TraceIdFilter.TRACE_ID_HEADER, "响应头名是本片对外的唯一口径");
    }

    /** 在两种常见工作目录里定位 logback-spring.xml（Maven 模块目录 / 仓库根），再各向上退一层兜底 */
    private Path locateLogbackConfig() {
        String[] candidates = {
                "src/main/resources/logback-spring.xml",
                "backEnd/src/main/resources/logback-spring.xml",
                "../backEnd/src/main/resources/logback-spring.xml"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
