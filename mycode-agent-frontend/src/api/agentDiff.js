import request from "@/utils/request";

/**
 * 查看本会话 Agent 在沙箱内改动过的文件 vs 原项目的 unified diff
 * GET /agent/workspace-diff?sessionId=&projectId=
 *
 * 返回数组，每项形如：
 *   { path, status, additions, deletions, unifiedDiff, oldContent, newContent }
 */
export function fetchWorkspaceDiff(sessionId, projectId) {
  return request.get("/agent/workspace-diff", {
    params: { sessionId, projectId },
  });
}

/**
 * 把沙箱改动应用到原项目（覆盖文件 + 强制 reindex）
 * POST /agent/apply-diff?sessionId=&projectId=
 * 返回应用了多少个文件（number）
 */
export function applyWorkspaceDiff(sessionId, projectId) {
  return request.post("/agent/apply-diff", null, {
    params: { sessionId, projectId },
  });
}

/**
 * 丢弃本会话 diff 记录（沙箱文件保留）
 * POST /agent/discard-diff?sessionId=
 */
export function discardWorkspaceDiff(sessionId) {
  return request.post("/agent/discard-diff", null, {
    params: { sessionId },
  });
}
