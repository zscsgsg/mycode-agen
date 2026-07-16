import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { uuid } from "@/utils/uuid";
import * as convApi from "@/api/conversation";

/**
 * 会话 store：管理 assistant / agent 两种模式下的会话列表、当前会话、消息列表
 */
export const useConversationStore = defineStore("conversation", () => {
  // 当前模式：assistant | agent
  const mode = ref("assistant");

  // 两种模式下的会话列表（来自后端）
  const conversationsByMode = ref({ assistant: [], agent: [] });

  // 当前激活的 conversationId（按模式分别保存）
  const activeIdByMode = ref({ assistant: "", agent: "" });

  // 当前会话消息列表（仅保存激活会话的，切换会话时重新拉取）
  // 结构：[{ id, role: 'user'|'assistant', content, createdAt, streaming?: bool }]
  const messages = ref([]);

  // 是否正在请求（用于禁用输入）
  const sending = ref(false);

  // 会话列表加载状态
  const listLoading = ref(false);
  const listError = ref("");

  // ---------- getters ----------
  const conversations = computed(
    () => conversationsByMode.value[mode.value] || [],
  );
  const activeId = computed(() => activeIdByMode.value[mode.value] || "");
  const activeConversation = computed(
    () => conversations.value.find((c) => c.id === activeId.value) || null,
  );

  // ---------- 标题工具 ----------
  /**
   * 判断是否为占位标题：空 / 新会话 / 任务 / 带时间后缀的“新会话 12:34:56”等
   */
  function isPlaceholderTitle(t) {
    if (!t) return true;
    const s = String(t).trim();
    if (!s) return true;
    if (s === "新会话" || s === "任务") return true;
    if (/^新会话(\s|$)/.test(s)) return true;
    if (/^任务(\s|$)/.test(s)) return true;
    return false;
  }

  /**
   * 根据创建时间生成友好回退标题：如“5月13日对话”
   */
  function fallbackTitleByTime(ts) {
    if (!ts) return "新会话";
    const d = new Date(ts);
    if (isNaN(d.getTime())) return "新会话";
    return `${d.getMonth() + 1}月${d.getDate()}日对话`;
  }

  /**
   * 计算用于显示的会话标题：
   * - 优先使用后端真实 title
   * - 占位标题则回退为按时间生成的友好标题
   */
  function getDisplayTitle(conv) {
    if (!conv) return "";
    if (!isPlaceholderTitle(conv.title)) return conv.title;
    return fallbackTitleByTime(conv.updateTime || conv.createTime);
  }

  /**
   * 从首条用户消息提炼标题（≤20 字，去除多余空白/换行）
   */
  function summarizeFirstMessage(msg) {
    if (!msg) return "";
    const cleaned = String(msg).replace(/\s+/g, " ").trim();
    if (!cleaned) return "";
    return cleaned.length > 20 ? cleaned.slice(0, 20) + "…" : cleaned;
  }

  // ---------- actions ----------
  function setMode(next) {
    if (next !== "assistant" && next !== "agent") return;
    mode.value = next;
  }

  async function fetchConversations() {
    listLoading.value = true;
    listError.value = "";
    try {
      const list = await convApi.listConversations(mode.value);
      conversationsByMode.value[mode.value] = Array.isArray(list) ? list : [];
    } catch (e) {
      console.error("加载会话列表失败", e);
      listError.value = e?.message || "加载失败";
      conversationsByMode.value[mode.value] = [];
    } finally {
      listLoading.value = false;
    }
  }

  /**
   * 新建会话：由后端生成真实 conversationId 后回填
   * 后端返回结构：{ conversationId, createdAt, title }
   */
  async function createConversation(title) {
    const now = Date.now();
    const finalTitle = title || "新会话";
    try {
      const created = await convApi.createConversation({
        mode: mode.value,
        title: finalTitle,
      });
      const id = created?.conversationId || created?.id;
      if (!id) throw new Error("后端未返回 conversationId");

      const conv = {
        id,
        mode: mode.value,
        title: created?.title || finalTitle,
        createTime: created?.createdAt || now,
        updateTime: created?.createdAt || now,
      };
      const list = conversationsByMode.value[mode.value];
      if (!list.some((c) => c.id === id)) list.unshift(conv);
      activeIdByMode.value[mode.value] = id;
      messages.value = [];
      // 拉一次最新列表对齐顺序/时间
      fetchConversations();
      return id;
    } catch (e) {
      console.error("创建会话失败，使用本地临时会话", e);
      listError.value = "创建失败：" + (e?.message || "");
      // 兜底：前端生成临时 id（带 local- 前缀标记），仅本地可用
      const tempId = "local-" + uuid();
      const fallback = {
        id: tempId,
        mode: mode.value,
        title: finalTitle,
        createTime: now,
        updateTime: now,
        _local: true,
      };
      conversationsByMode.value[mode.value].unshift(fallback);
      activeIdByMode.value[mode.value] = tempId;
      messages.value = [];
      return tempId;
    }
  }

  /**
   * 更新会话标题
   */
  async function renameConversation(id, title) {
    const newTitle = (title || "").trim();
    if (!id || !newTitle) return;
    const conv = conversationsByMode.value[mode.value].find((c) => c.id === id);
    if (conv && conv.title === newTitle) return;
    if (id.startsWith("local-")) {
      // 本地会话只改本地
      if (conv) conv.title = newTitle;
      return;
    }
    try {
      await convApi.updateConversationTitle(id, newTitle);
      if (conv) conv.title = newTitle;
    } catch (e) {
      console.error("更新会话标题失败", e);
      throw e;
    }
  }

  /**
   * 若会话标题为空或为占位标题，则按首条用户消息自动生成标题并提交后端。
   * 通常在用户发送会话第一条消息后调用。
   */
  async function autoTitleFromFirstMessage(id, firstMessage) {
    if (!id) return;
    const conv = conversationsByMode.value[mode.value].find((c) => c.id === id);
    if (!conv) return;
    if (!isPlaceholderTitle(conv.title)) return; // 已有真实标题，不覆盖
    const summary = summarizeFirstMessage(firstMessage);
    if (!summary) return;
    try {
      await renameConversation(id, summary);
      // 刷新列表，对齐后端排序/时间
      fetchConversations();
    } catch (_) {
      // 失败不阻塞主流程
    }
  }

  async function switchConversation(id) {
    activeIdByMode.value[mode.value] = id;
    await loadMessages(id);
  }

  async function deleteConversation(id) {
    try {
      await convApi.deleteConversation(id);
    } catch (e) {
      console.warn("删除会话接口失败，继续本地移除", e);
    }
    const list = conversationsByMode.value[mode.value];
    conversationsByMode.value[mode.value] = list.filter((c) => c.id !== id);
    if (activeIdByMode.value[mode.value] === id) {
      activeIdByMode.value[mode.value] = "";
      messages.value = [];
    }
  }

  async function loadMessages(id) {
    if (!id) {
      messages.value = [];
      return;
    }
    try {
      const list = await convApi.getMessages(id);
      messages.value = (list || []).map((m) => ({
        id: m.id || uuid(),
        role: m.role || (m.type === "USER" ? "user" : "assistant"),
        content: m.content || "",
        createdAt: m.createdAt || m.createTime || Date.now(),
      }));
    } catch (e) {
      console.error("加载会话消息失败", e);
      messages.value = [];
    }
  }

  // ---------- 消息流操作 ----------
  function appendUserMessage(content) {
    messages.value.push({
      id: uuid(),
      role: "user",
      content,
      createdAt: Date.now(),
    });
  }

  /**
   * 创建一个空的 AI 占位消息，后续流式追加内容
   */
  function createStreamingAssistantMessage() {
    const msg = {
      id: uuid(),
      role: "assistant",
      content: "",
      createdAt: Date.now(),
      streaming: true,
    };
    messages.value.push(msg);
    return msg;
  }

  function appendChunk(msgId, chunk) {
    const msg = messages.value.find((m) => m.id === msgId);
    if (msg) msg.content += chunk;
  }

  function finishStreaming(msgId) {
    const msg = messages.value.find((m) => m.id === msgId);
    if (msg) msg.streaming = false;
  }

  function setSending(v) {
    sending.value = v;
  }

  function clearMessages() {
    messages.value = [];
  }

  return {
    // state
    mode,
    conversationsByMode,
    activeIdByMode,
    messages,
    sending,
    listLoading,
    listError,
    // getters
    conversations,
    activeId,
    activeConversation,
    // actions
    setMode,
    fetchConversations,
    createConversation,
    switchConversation,
    deleteConversation,
    loadMessages,
    appendUserMessage,
    createStreamingAssistantMessage,
    appendChunk,
    finishStreaming,
    setSending,
    clearMessages,
    renameConversation,
    autoTitleFromFirstMessage,
    // helpers
    getDisplayTitle,
    isPlaceholderTitle,
    summarizeFirstMessage,
  };
});
