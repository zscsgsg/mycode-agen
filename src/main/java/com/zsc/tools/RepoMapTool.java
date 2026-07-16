package com.zsc.tools;

import com.zsc.indexing.RepoMapService;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * 项目级符号摘要工具：返回原项目所有 .java 文件的包名、类签名、字段、方法签名摘要。
 * 在「未知项目第一次接触」时优先调用，比 listDir / searchCode 更快建立全局认知。
 */
@Slf4j
public class RepoMapTool implements Function<RepoMapTool.Request, String> {

    private final RepoMapService repoMapService;
    private final String projectId;

    public RepoMapTool(RepoMapService repoMapService, String projectId) {
        this.repoMapService = repoMapService;
        this.projectId = projectId;
    }

    public static class Request {
        private String path;
        private String keyword;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
    }

    @Override
    public String apply(Request request) {
        String path = request == null ? null : request.getPath();
        String keyword = request == null ? null : request.getKeyword();
        log.info("[getRepoMap] 调用 projectId={}, path={}, keyword={}", projectId, path, keyword);
        if (projectId == null || projectId.isBlank()) {
            return "错误：当前会话未关联项目，无法生成 RepoMap。";
        }
        return repoMapService.query(projectId, path, keyword);
    }
}
