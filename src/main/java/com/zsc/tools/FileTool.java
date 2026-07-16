package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 写入工作区文件（全量覆盖）。备份/索引同步等共用逻辑由 {@link WorkspaceWriter} 承担。
 * 适合「新建文件」或「改动量超过文件 60% 的全量覆盖」。
 * 行级、几十行内的小修改优先用 {@link EditFileTool}（editFile）。
 */
@Slf4j
public class FileTool implements Function<FileTool.Request, String> {

    private final WorkspaceWriter workspaceWriter;

    public FileTool(WorkspaceWriter workspaceWriter) {
        this.workspaceWriter = workspaceWriter;
    }

    public static class Request {
        private String relativePath;
        private String content;

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    @Override
    public String apply(Request request) {
        String relativePath = request == null ? null : request.getRelativePath();
        int contentLen = request == null || request.getContent() == null ? 0 : request.getContent().length();
        log.info("[writeFile] 调用 relativePath={}, contentLen={}", relativePath, contentLen);
        if (relativePath == null || relativePath.isBlank()) {
            return "错误：relativePath 不能为空";
        }
        Path target = workspaceWriter.resolveWritePath(relativePath);
        try {
            workspaceWriter.persist(target, relativePath, request.getContent() == null ? "" : request.getContent());
            log.info("[writeFile] 写入完成: {}", target);
            return "成功写入文件: " + target;
        } catch (IOException e) {
            log.error("[writeFile] 写入失败", e);
            return "写入失败: " + e.getMessage();
        }
    }
}
