package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于「Search/Replace 块」的精准编辑工具。
 * <p>
 * AI 提交一组 (original, replacement) 块，工具按顺序在文件中查找 original 并替换为 replacement。
 * 比 writeFile 全量覆盖更省 token、更不易破坏未预期内容。
 * <p>
 * 唯一性约束：每一块的 original 必须在当前文本中恰好出现 1 次。
 * 出现 0 次 → 提示加上下文重试；出现 N 次 → 提示补足上下文使其唯一。
 */
@Slf4j
public class EditFileTool implements Function<EditFileTool.Request, String> {

    private final WorkspaceWriter workspaceWriter;

    public EditFileTool(WorkspaceWriter workspaceWriter) {
        this.workspaceWriter = workspaceWriter;
    }

    public static class Request {
        private String relativePath;
        private List<EditBlock> edits;

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
        public List<EditBlock> getEdits() { return edits; }
        public void setEdits(List<EditBlock> edits) { this.edits = edits; }
    }

    public static class EditBlock {
        private String original;
        private String replacement;

        public String getOriginal() { return original; }
        public void setOriginal(String original) { this.original = original; }
        public String getReplacement() { return replacement; }
        public void setReplacement(String replacement) { this.replacement = replacement; }
    }

    @Override
    public String apply(Request request) {
        String relativePath = request == null ? null : request.getRelativePath();
        List<EditBlock> edits = request == null ? null : request.getEdits();
        log.info("[editFile] 调用 relativePath={}, blocks={}", relativePath,
                edits == null ? 0 : edits.size());

        if (relativePath == null || relativePath.isBlank()) {
            return "错误：relativePath 不能为空";
        }
        if (edits == null || edits.isEmpty()) {
            return "错误：edits 不能为空。每个元素需包含 original 和 replacement 字段。";
        }

        Path target;
        try {
            target = workspaceWriter.resolveOrCopy(relativePath);
        } catch (IOException e) {
            log.error("[editFile] 定位文件失败 {}", relativePath, e);
            return "定位文件失败: " + e.getMessage();
        }
        if (target == null || !Files.exists(target)) {
            return "文件不存在: " + relativePath + "（工作区与原项目都未找到。新建文件请用 writeFile。）";
        }

        String raw;
        try {
            raw = Files.readString(target);
        } catch (IOException e) {
            log.error("[editFile] 读取失败 {}", target, e);
            return "读取失败: " + e.getMessage();
        }
        // CRLF 归一化：在 LF 空间做匹配/替换，最后写回时按原始风格还原
        boolean wasCRLF = raw.contains("\r\n");
        String text = raw.replace("\r\n", "\n");

        List<String> applied = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            EditBlock block = edits.get(i);
            String original = block == null ? null : block.getOriginal();
            String replacement = block == null ? null : block.getReplacement();
            if (original == null || original.isEmpty()) {
                return "错误：第 " + (i + 1) + " 块的 original 为空。";
            }
            if (replacement == null) replacement = "";
            // 同步对 needle/replacement 做 LF 归一
            original = original.replace("\r\n", "\n");
            replacement = replacement.replace("\r\n", "\n");

            int count = countOccurrences(text, original);
            int idx;
            int matchedLen;
            if (count == 1) {
                idx = text.indexOf(original);
                matchedLen = original.length();
            } else if (count > 1) {
                return "错误：第 " + (i + 1) + " 块在文件中出现 " + count + " 次（不唯一）。" +
                        "请扩大 original 的上下文（前后多包含几行），使其在文件中只出现一次后重试。\n" +
                        "提示：original 第一行=「" + firstLine(original) + "」";
            } else {
                // 严格匹配失败 → 宽松匹配兜底（行尾空白 + 行间换行容忍）
                int[] loose = looseFind(text, original);
                if (loose == null) {
                    String firstLine = firstLine(original).trim();
                    boolean firstLineExists = !firstLine.isEmpty() && text.contains(firstLine);
                    return "错误：第 " + (i + 1) + " 块未找到匹配片段。" +
                            "请确认空白/缩进/空行数量与原文一致，或加更多上下文后重试。\n" +
                            "提示：original 第一行=「" + firstLine(original) + "」，" +
                            "该行在文件中" + (firstLineExists ? "存在" : "不存在") + "。\n" +
                            "建议：先用 readFile 读最新内容再编辑；或缩小 original 范围到 1~3 行更稳定的代码。";
                }
                if (loose[0] == -2) {
                    return "错误：第 " + (i + 1) + " 块（宽松匹配下）在文件中出现多次。" +
                            "请扩大 original 的上下文使其唯一。\n" +
                            "提示：original 第一行=「" + firstLine(original) + "」";
                }
                idx = loose[0];
                matchedLen = loose[1] - loose[0];
                log.info("[editFile] 第 {} 块通过宽松匹配命中（行尾空白/换行差异已容忍）", i + 1);
            }

            text = text.substring(0, idx) + replacement + text.substring(idx + matchedLen);
            int line = countLinesBefore(text, idx);
            applied.add("第 " + (i + 1) + " 块：约第 " + line + " 行（删 "
                    + lineCount(original) + " 行 / 增 " + lineCount(replacement) + " 行）");
        }

        // 还原换行风格
        String finalText = wasCRLF ? text.replace("\n", "\r\n") : text;
        try {
            workspaceWriter.persist(target, relativePath, finalText);
        } catch (IOException e) {
            log.error("[editFile] 写入失败 {}", target, e);
            return "写入失败: " + e.getMessage();
        }

        log.info("[editFile] 完成 file={} blocks={}", target, applied.size());
        StringBuilder sb = new StringBuilder();
        sb.append("成功编辑 ").append(relativePath).append("，应用了 ").append(applied.size()).append(" 处修改：\n");
        applied.forEach(s -> sb.append(" - ").append(s).append("\n"));
        return sb.toString();
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) break;
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    private static int countLinesBefore(String text, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static int lineCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }

    /**
     * 宽松匹配：把 needle 按行拆开，每行允许尾部空白差异，行间换行允许 \r?\n。
     * 返回 {start, end}；未命中返回 null；命中多次返回 {-2,-2}。
     */
    private static int[] looseFind(String text, String needle) {
        String[] lines = needle.split("\n", -1);
        StringBuilder pat = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) pat.append("[ \\t]*\\r?\\n");
            String line = lines[i].replaceAll("[ \\t]+$", "");
            pat.append(Pattern.quote(line));
            pat.append("[ \\t]*");
        }
        Matcher m;
        try {
            m = Pattern.compile(pat.toString()).matcher(text);
        } catch (Exception e) {
            return null;
        }
        if (!m.find()) return null;
        int s = m.start(), e = m.end();
        if (m.find()) return new int[]{-2, -2};
        return new int[]{s, e};
    }
}
