<template>
  <div class="layout">
    <Sidebar
      :collapsed="sbCollapsed"
      mode="assistant"
      :mobile-open="isMobile && mobileTab === 'sidebar'"
      @toggle="sbCollapsed = !sbCollapsed"
      @close="mobileTab = 'chat'"
    />

    <ChatPanel
      class="layout-center"
      title="编程助手（RAG 模式）"
      :subtitle="subtitle"
      :messages="convStore.messages"
      :sending="convStore.sending"
      placeholder="基于已上传项目提问，例如：UserService 里怎么做权限校验？"
      empty-text="上传项目文件并新建会话，即可开始提问"
      @send="handleSend"
      @cancel="handleCancel"
    >
      <template #notice>
        <div v-if="showVectorBanner" class="info-bar">
          🔄 智能体刚改动了
          <b>{{ projectStore.recentChangedFiles.length }}</b>
          个文件，向量索引已自动更新
          <span
            class="files"
            v-if="projectStore.recentChangedFiles.length"
            :title="projectStore.recentChangedFiles.join('\n')"
          >
            （{{ projectStore.recentChangedFiles.slice(0, 2).join("、") }}
            {{ projectStore.recentChangedFiles.length > 2 ? " 等" : "" }}）
          </span>
          <button class="close" @click="dismissBanner">×</button>
        </div>
        <div v-if="errorMsg" class="err-bar">{{ errorMsg }}</div>
      </template>
    </ChatPanel>

    <RetrievedDocsPanel
      :collapsed="rpCollapsed"
      :mobile-open="isMobile && mobileTab === 'docs'"
      @toggle="rpCollapsed = !rpCollapsed"
      @close="mobileTab = 'chat'"
    />

    <!-- 移动端底部导航栏 -->
    <nav v-if="isMobile" class="mobile-nav">
      <button
        class="nav-btn"
        :class="{ active: mobileTab === 'sidebar' }"
        @click="mobileTab = mobileTab === 'sidebar' ? 'chat' : 'sidebar'"
      >
        <span class="nav-icon">☰</span>
        <span class="nav-label">会话</span>
      </button>
      <button
        class="nav-btn"
        :class="{ active: mobileTab === 'chat' }"
        @click="mobileTab = 'chat'"
      >
        <span class="nav-icon">💬</span>
        <span class="nav-label">对话</span>
      </button>
      <button
        class="nav-btn"
        :class="{ active: mobileTab === 'docs' }"
        @click="mobileTab = mobileTab === 'docs' ? 'chat' : 'docs'"
      >
        <span class="nav-icon">🔍</span>
        <span class="nav-label">检索</span>
        <span v-if="retrievedStore.total > 0" class="nav-dot"></span>
      </button>
    </nav>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import Sidebar from "@/components/Sidebar.vue";
import ChatPanel from "@/components/ChatPanel.vue";
import RetrievedDocsPanel from "@/components/RetrievedDocsPanel.vue";
import { useConversationStore } from "@/stores/conversation";
import { useProjectStore } from "@/stores/project";
import { useRetrievedDocsStore } from "@/stores/retrievedDocs";
import { chatStream } from "@/api/assistant";

const convStore = useConversationStore();
const projectStore = useProjectStore();
const retrievedStore = useRetrievedDocsStore();

// 在子组件挂载前先设置模式，保证 ConversationList 首次 fetch 即为 assistant
convStore.setMode("assistant");
projectStore.ensureProjectId();

const sbCollapsed = ref(false);
const rpCollapsed = ref(false);
const errorMsg = ref("");
let abortCtrl = null;

// 移动端检测
const isMobile = ref(window.innerWidth <= 768);
const mobileTab = ref("chat");
function onResize() {
  isMobile.value = window.innerWidth <= 768;
  if (!isMobile.value) mobileTab.value = "chat";
}
window.addEventListener("resize", onResize);
onUnmounted(() => window.removeEventListener("resize", onResize));

// 向量更新 banner：3 秒后自动消失
const showVectorBanner = ref(false);
let bannerTimer = null;
watch(
  () => projectStore.vectorsUpdatedAt,
  (ts) => {
    if (!ts) return;
    showVectorBanner.value = true;
    if (bannerTimer) clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => {
      showVectorBanner.value = false;
    }, 3000);
  },
);
function dismissBanner() {
  showVectorBanner.value = false;
  if (bannerTimer) clearTimeout(bannerTimer);
}

onMounted(() => {
  if (convStore.activeId && !convStore.messages.length) {
    convStore.loadMessages(convStore.activeId);
  }
});

const subtitle = computed(() => {
  const conv = convStore.activeConversation;
  const t = conv ? convStore.getDisplayTitle(conv) : "";
  return t ? `当前会话：${t}` : "未选择会话（发送时会自动创建）";
});

async function ensureConversation() {
  if (!convStore.activeId) {
    await convStore.createConversation(
      "新会话 " + new Date().toLocaleTimeString(),
    );
  }
  return convStore.activeId;
}

async function handleSend(text) {
  errorMsg.value = "";
  const pid = projectStore.ensureProjectId();
  const cid = await ensureConversation();

  const isFirstMsg = convStore.messages.length === 0;

  // 清空上一次的检索片段，重新接收
  retrievedStore.reset();

  convStore.appendUserMessage(text);
  const aiMsg = convStore.createStreamingAssistantMessage();
  convStore.setSending(true);
  abortCtrl = new AbortController();

  if (isFirstMsg) {
    convStore.autoTitleFromFirstMessage(cid, text);
  }

  await chatStream({
    conversationId: cid,
    projectId: pid,
    message: text,
    signal: abortCtrl.signal,
    onText: (chunk) => convStore.appendChunk(aiMsg.id, chunk),
    onEvent: ({ event, data }) => {
      if (event === "retrieved_docs") {
        retrievedStore.setFromPayload(data);
      } else if (event === "message") {
        convStore.appendChunk(aiMsg.id, data || "");
      } else if (event === "done") {
        // 由 onDone 兜底处理，避免重复
      } else if (event === "error") {
        errorMsg.value = "服务端错误：" + (data || "");
      } else {
        // 兜底：未知命名事件作为文本
        convStore.appendChunk(aiMsg.id, data || "");
      }
    },
    onDone: () => {
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      convStore.fetchConversations();
    },
    onError: (err) => {
      convStore.appendChunk(aiMsg.id, "\n\n[错误] " + err.message);
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      errorMsg.value = "请求失败：" + err.message;
    },
  });
}

function handleCancel() {
  abortCtrl?.abort();
}
</script>

<style scoped>
.err-bar {
  background: rgba(239, 68, 68, 0.1);
  color: var(--red);
  border-top: 1px solid rgba(239, 68, 68, 0.25);
  padding: 8px 20px;
  font-size: 12px;
}
.info-bar {
  background: rgba(34, 197, 94, 0.08);
  color: var(--green);
  border-top: 1px solid rgba(34, 197, 94, 0.2);
  padding: 8px 20px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.info-bar .files {
  color: var(--green);
  font-family: "JetBrains Mono", monospace;
  font-size: 11px;
}
.info-bar .close {
  margin-left: auto;
  border: none;
  background: transparent;
  font-size: 16px;
  cursor: pointer;
  color: var(--green);
  line-height: 1;
}

/* 移动端底部导航栏 */
.mobile-nav {
  display: none;
}
@media (max-width: 768px) {
  .mobile-nav {
    display: flex;
    justify-content: space-around;
    align-items: center;
    height: 52px;
    padding-bottom: env(safe-area-inset-bottom, 0px);
    background: var(--bg-glass);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    border-top: 1px solid var(--border-subtle);
    flex-shrink: 0;
  }
  .nav-btn {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    border: none;
    background: transparent;
    color: var(--text-muted);
    font-size: 10px;
    padding: 4px 12px;
    position: relative;
    cursor: pointer;
    transition: color 0.2s;
    -webkit-tap-highlight-color: transparent;
  }
  .nav-btn.active {
    color: var(--accent);
  }
  .nav-icon {
    font-size: 18px;
    line-height: 1;
  }
  .nav-label {
    font-size: 10px;
    line-height: 1;
  }
  .nav-dot {
    position: absolute;
    top: 6px;
    right: 8px;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--red);
  }
}
</style>
