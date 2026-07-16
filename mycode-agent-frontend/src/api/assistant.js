import request from "@/utils/request";
import { postSSE } from "@/utils/sse";

/**
 * 文件上传（编程助手）
 * POST /assistant/upload  (multipart/form-data)
 *
 * 支持上传整个项目文件夹，保留相对路径：
 * - 可传入 File（使用 webkitRelativePath 作为路径，不存在则退化为 file.name）
 * - 也可传入 { file, path } 结构（拖拽文件夹场景下手动计算的路径）
 *
 * 后端取文件名时会拿到路径形式的文件名，如 "src/main/UserService.java"
 *
 * @param {string} projectId
 * @param {Array<File|{file:File, path:string}>} files
 */
export function uploadFiles(projectId, files) {
  const form = new FormData();
  form.append("projectId", projectId);
  for (const item of files) {
    const file = item?.file || item;
    if (!(file instanceof File) && !(file instanceof Blob)) continue;
    const path =
      item?.path || file.webkitRelativePath || file.relativePath || file.name;
    // FormData.append 的第三个参数 作为 multipart 的 filename
    form.append("files", file, path);
  }
  return request.post("/assistant/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
    // 上传单独调到 10 分钟，适配后端向量化/索引等耗时场景
    timeout: 600000,
  });
}

/**
 * 删除项目向量索引
 * DELETE /assistant/deleteVectors?projectId=xxx
 *
 * @param {string} projectId
 */
export function deleteProjectVectors(projectId) {
  return request.delete("/assistant/deleteVectors", {
    params: { projectId },
  });
}

/**
 * 查询已上传的文件（断点续传用）
 * GET /assistant/upload/check-exist?projectId=xxx&fileNames=a,b,c
 *
 * 返回 { existing: ["src/main/java/UserService.java", ...] }
 * 前端据此跳过已存在的文件，只传未上传的。
 *
 * @param {string} projectId
 * @param {string[]} fileNames - 文件名列表（相对路径）
 * @returns {Promise<{existing: string[]}>}
 */
export function checkUploadedFiles(projectId, fileNames) {
  return request.get("/assistant/upload/check-exist", {
    params: { projectId, fileNames: fileNames.join(",") },
  });
}

/**
 * 编程助手问答（SSE 流式）
 * POST /chat-message/chat?conversationId=&projectId=&message=
 */
export function chatStream({
  conversationId,
  projectId,
  message,
  signal,
  onText,
  onEvent,
  onDone,
  onError,
}) {
  return postSSE("/api/chat-message/chat", {
    params: { conversationId, projectId, message },
    signal,
    onText,
    onEvent,
    onDone,
    onError,
  });
}
