import request from "@/utils/request";

/**
 * 会话管理 API（严格对齐后端 ConversationController）
 *
 * 后端接口：
 *   POST   /conversation/create          body: { mode, title }              → { conversationId, createdAt, title }
 *   GET    /conversation/list?mode=xxx                                       → ConversationVO[]
 *   PUT    /conversation/update          body: { id, title }                 → "更新成功"
 *   DELETE /conversation/delete/{id}                                          → void
 *   GET    /conversation/{id}/messages                                        → ChatMessage[]
 *
 * 说明：
 * - 后端所有接口统一 ResponseEntity<ApiResult<T>>，ApiResult = { code, message, data }
 *   由 axios 拦截器自动解包为 data
 * - mode 必须是小写："assistant" | "agent"，其它值后端返回 400
 * - conversationId 由后端生成，前端必须使用响应里的 conversationId，不能自己生成
 * - 默认 Accept: application/json 已在 request.js 注入
 */

/**
 * 新建会话
 * @param {{ mode: 'assistant'|'agent', title?: string }} payload
 * @returns {Promise<{ conversationId: string, createdAt: number|string, title: string }>}
 */
export function createConversation({ mode, title }) {
  return request.post("/conversation/create", {
    mode,
    title: title || "新会话",
  });
}

/**
 * 获取会话列表
 * @param {'assistant'|'agent'} mode
 * @returns {Promise<Array>}
 */
export async function listConversations(mode) {
  const data = await request.get("/conversation/list", { params: { mode } });
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.list)) return data.list;
  if (Array.isArray(data?.records)) return data.records;
  return [];
}

/**
 * 更新会话标题
 * @param {string} id
 * @param {string} title
 */
export function updateConversationTitle(id, title) {
  return request.put("/conversation/update", { id, title });
}

/**
 * 删除会话
 */
export function deleteConversation(id) {
  return request.delete(`/conversation/delete/${id}`);
}

/**
 * 获取某个会话的历史消息
 */
export async function getMessages(id) {
  const data = await request.get(`/conversation/${id}/messages`);
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.list)) return data.list;
  if (Array.isArray(data?.messages)) return data.messages;
  return [];
}
