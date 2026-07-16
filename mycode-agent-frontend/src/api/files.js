import request from "@/utils/request";

/** 获取工作区文件树（含与原项目的差异状态：unchanged/modified/new） */
export function fetchTree(sessionId, projectId) {
  return request.get("/agent/files/tree", { params: { sessionId, projectId } });
}

/** 获取文件内容；source: workspace | original */
export function fetchContent(
  sessionId,
  projectId,
  relativePath,
  source = "workspace",
) {
  return request.get("/agent/files/content", {
    params: { sessionId, projectId, relativePath, source },
  });
}

/** 获取与原项目的 unified diff（含两端原文） */
export function fetchDiff(sessionId, projectId, relativePath) {
  return request.get("/agent/files/diff", {
    params: { sessionId, projectId, relativePath },
  });
}

/** 列出某文件的所有备份 */
export function fetchBackups(sessionId, relativePath) {
  return request.get("/agent/files/backups", {
    params: { sessionId, relativePath },
  });
}

/** 恢复文件：backupName 留空则用原项目原始版本恢复 */
export function restoreFile(sessionId, projectId, relativePath, backupName) {
  return request.post("/agent/files/restore", null, {
    params: { sessionId, projectId, relativePath, backupName },
  });
}
