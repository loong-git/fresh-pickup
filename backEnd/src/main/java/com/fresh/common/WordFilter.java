package com.fresh.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词过滤组件（T-M4-05）：
 * - 词表 classpath:sensitive-words.txt，每行一词，第二列标注 soft 为软词，无标注为硬词，# 注释行；
 * - 匹配策略：简单包含匹配（契约允许 DFA 或简单包含），硬词命中即拒，软词命中转人工审核；
 * - 热更新：每次校验前比对词表文件 mtime，变化即重载（读侧 volatile 引用无锁，重载原子替换）；
 *   文件系统部署（dev 运行 = target/classes 下真实文件）支持热更新；
 *   jar 内部署 ClassPathResource#getFile 不可达，退化为一次性加载（生产重启生效）。
 */
@Slf4j
@Component
public class WordFilter {

    private static final String WORD_FILE = "sensitive-words.txt";
    /** 词表软词标注列 */
    private static final String SOFT_MARK = "soft";

    /** 硬词表：命中直接 400 拒绝 */
    private volatile List<String> hardWords = List.of();
    /** 软词表：命中标记 audit_status=pending */
    private volatile List<String> softWords = List.of();
    /** 词表文件（文件系统可达时非空，支持 mtime 热更新） */
    private volatile File wordFile;
    private volatile long lastModified = -1L;

    @PostConstruct
    public void load() {
        ClassPathResource resource = new ClassPathResource(WORD_FILE);
        File file = null;
        try {
            file = resource.getFile(); // jar 内部署会抛 IOException，见下方 catch
        } catch (IOException ignored) {
            // 退化路径：从 classpath 流读取，无法 stat mtime，不做热更新
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file != null ? new FileInputStream(file) : resource.getInputStream(),
                StandardCharsets.UTF_8))) {
            parseAndSwap(reader);
            this.wordFile = file;
            this.lastModified = file != null ? file.lastModified() : -1L;
            log.info("敏感词库已加载: hardWords={}, softWords={}, hotReload={}",
                    hardWords.size(), softWords.size(), file != null);
        } catch (IOException e) {
            log.error("敏感词库加载失败，过滤退化为空词表", e);
        }
    }

    /** 命中硬词返回该词（供日志/提示定位），未命中返回 null */
    public String matchHard(String text) {
        refreshIfChanged();
        return match(hardWords, text);
    }

    /** 命中软词返回该词，未命中返回 null */
    public String matchSoft(String text) {
        refreshIfChanged();
        return match(softWords, text);
    }

    /** mtime 变化 → 同步重载（单次文件 stat 开销极小，评价提交本身有限流，无需额外节流） */
    private void refreshIfChanged() {
        File file = wordFile;
        if (file == null) {
            return; // jar 内部署无独立文件，不支持热更新
        }
        long mtime = file.lastModified();
        if (mtime == lastModified) {
            return;
        }
        synchronized (this) {
            if (file.lastModified() == lastModified) {
                return; // 双检：并发请求只需一次重载
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                parseAndSwap(reader);
                lastModified = file.lastModified();
                log.info("敏感词库热更新: hardWords={}, softWords={}", hardWords.size(), softWords.size());
            } catch (IOException e) {
                log.error("敏感词库热更新失败，沿用旧词表", e);
            }
        }
    }

    /** 简单包含匹配：命中返回首个命中的词，未命中/空入参返回 null */
    private String match(List<String> words, String text) {
        if (text == null || text.isEmpty() || words.isEmpty()) {
            return null;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 解析词表并整体原子替换（volatile 引用，读侧无锁）；解析在本次加载内完成，失败不污染旧词表 */
    private void parseAndSwap(BufferedReader reader) throws IOException {
        List<String> hard = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // 空行与注释行
            }
            // 去 UTF-8 BOM（文件头粘贴场景）
            if (trimmed.startsWith("\uFEFF")) {
                trimmed = trimmed.substring(1).trim();
            }
            String[] parts = trimmed.split("[,，]", 2); // 兼容中英文逗号分隔
            String word = parts[0].trim();
            if (word.isEmpty()) {
                continue;
            }
            if (parts.length > 1 && SOFT_MARK.equalsIgnoreCase(parts[1].trim())) {
                soft.add(word);
            } else {
                hard.add(word);
            }
        }
        this.hardWords = List.copyOf(hard);
        this.softWords = List.copyOf(soft);
    }
}
