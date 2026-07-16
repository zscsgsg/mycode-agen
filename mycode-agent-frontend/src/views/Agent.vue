<template>
  <div class="layout">
    <Sidebar
      :collapsed="sbCollapsed"
      mode="agent"
      :mobile-open="isMobile && mobileTab === 'sidebar'"
      @toggle="sbCollapsed = !sbCollapsed"
      @close="mobileTab = 'chat'"
    />

    <ChatPanel
      class="layout-center"
      title="编程智能体（ReAct 模式）"
      :subtitle="subtitle"
      :messages="convStore.messages"
      :sending="convStore.sending"
      :show-model="true"
      :model-value="projectStore.selectedModel"
      @update:model-value="projectStore.selectedModel = $event"
      placeholder="描述任务，例如：在 UserService 中新增一个 resetPassword 方法并补充单元测试"
      empty-text="输入任务，智能体将自主规划并调用工具执行"
      @send="handleSend"
      @cancel="handleCancel"
    >
      <template #head-right>
        <div
          class="pid-chip"
          v-if="projectStore.projectId"
          :title="projectStore.projectId"
        >
          项目：{{ shortPid }}
        </div>
        <button
          v-if="projectStore.projectId"
          class="btn btn-ghost btn-sm diff-btn"
          @click="openDiffDrawer"
          title="查看本会话在沙箱里的修改并应用到原项目"
        >
          同步到原项目
          <span v-if="diffStore.badgeCount > 0" class="diff-badge">{{
            diffStore.badgeCount
          }}</span>
        </button>
        <router-link class="btn btn-ghost btn-sm" to="/assistant" v-else>
          先去上传项目 →
        </router-link>
      </template>

      <template #notice>
        <div v-if="!projectStore.projectId" class="warn-bar">
          当前未检测到项目，请前往
          <router-link to="/assistant">编程助手</router-link>
          上传代码文件后再使用智能体。
        </div>
        <div v-if="errorMsg" class="err-bar">{{ errorMsg }}</div>
      </template>

      <template #after-messages>
        <PlanCard @confirm="handlePlanConfirm" @cancel="handlePlanCancel" />
      </template>
    </ChatPanel>

    <ToolTracePanel
      :collapsed="tpCollapsed"
      :experimental="true"
      :mobile-open="isMobile && mobileTab === 'trace'"
      @toggle="tpCollapsed = !tpCollapsed"
      @close="mobileTab = 'chat'"
    />

    <DiffDrawer />

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
        :class="{ active: mobileTab === 'trace' }"
        @click="mobileTab = mobileTab === 'trace' ? 'chat' : 'trace'"
      >
        <span class="nav-icon">🔧</span>
        <span class="nav-label">工具</span>
        <span v-if="traceStore.traces.length" class="nav-dot"></span>
      </button>
    </nav>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import Sidebar from "@/components/Sidebar.vue";
import ChatPanel from "@/components/ChatPanel.vue";
import ToolTracePanel from "@/components/ToolTracePanel.vue";
import PlanCard from "@/components/PlanCard.vue";
import DiffDrawer from "@/components/DiffDrawer.vue";
import { useConversationStore } from "@/stores/conversation";
import { useProjectStore } from "@/stores/project";
import { useAgentTraceStore } from "@/stores/agentTrace";
import { useWorkspaceDiffStore } from "@/stores/workspaceDiff";
import {
  agentExecuteStream,
  generatePlan,
  executePlanStream,
} from "@/api/agent";

const convStore = useConversationStore();
const projectStore = useProjectStore();
const traceStore = useAgentTraceStore();
const diffStore = useWorkspaceDiffStore();

// 挂载前先设置模式，保证子组件首次 fetch 即为 agent
convStore.setMode("agent");

const sbCollapsed = ref(false);
const tpCollapsed = ref(false);
const errorMsg = ref("");
let abortCtrl = null;
let runningToolId = null;
let pendingTask = ref(""); // 暂存原始任务文本用于计划确认后执行

// 移动端检测
const isMobile = ref(window.innerWidth <= 768);
const mobileTab = ref("chat");
function onResize() {
  isMobile.value = window.innerWidth <= 768;
  if (!isMobile.value) mobileTab.value = "chat";
}
window.addEventListener("resize", onResize);
onUnmounted(() => window.removeEventListener("resize", onResize));

onMounted(() => {
  if (convStore.activeId && !convStore.messages.length) {
    convStore.loadMessages(convStore.activeId);
  }
  // 进入页面时刷新 badge（有改动记录时显示红点）
  if (convStore.activeId && projectStore.projectId) {
    diffStore.refreshBadge(convStore.activeId, projectStore.projectId);
  }
});

function openDiffDrawer() {
  if (!convStore.activeId || !projectStore.projectId) return;
  diffStore.open(convStore.activeId, projectStore.projectId);
}

const subtitle = computed(() => {
  const conv = convStore.activeConversation;
  const t = conv ? convStore.getDisplayTitle(conv) : "";
  return t ? `当前会话：${t}` : "未选择会话（发送时会自动创建）";
});

const shortPid = computed(() => {
  const p = projectStore.projectId;
  return p ? p.slice(0, 8) : "";
});

async function ensureConversation() {
  if (!convStore.activeId) {
    await convStore.createConversation(
      "任务 " + new Date().toLocaleTimeString(),
    );
  }
  return convStore.activeId;
}

async function handleSend(text) {
  errorMsg.value = "";
  if (!projectStore.projectId) {
    errorMsg.value = "请先前往编程助手上传项目文件";
    return;
  }
  const cid = await ensureConversation();
  const pid = projectStore.projectId;

  const isFirstMsg = convStore.messages.length === 0;
  convStore.appendUserMessage(text);
  if (isFirstMsg) {
    convStore.autoTitleFromFirstMessage(cid, text);
  }

  // Planner 流程：先生成计划
  pendingTask.value = text;
  traceStore.setPlanGenerating(text);
  convStore.setSending(true);

  try {
    const steps = await generatePlan(pid, text, projectStore.selectedModel);
    traceStore.setPlan(text, steps);
    convStore.setSending(false);
    // 等待用户在 PlanCard 点击"确认执行"
  } catch (e) {
    // 计划生成失败 → 降级为直接执行
    traceStore.clearPlan();
    executeDirectly(cid, pid, text);
  }
}

/** 用户确认计划后执行 */
async function handlePlanConfirm() {
  const cid = convStore.activeId;
  const pid = projectStore.projectId;
  const task = pendingTask.value;
  const steps = traceStore.planSteps.map((s) => ({
    step: s.step,
    title: s.title,
    description: s.description,
    type: s.type,
  }));

  traceStore.startPlanExecution();
  convStore.setSending(true);
  const aiMsg = convStore.createStreamingAssistantMessage();
  abortCtrl = new AbortController();

  await executePlanStream({
    sessionId: cid,
    conversationId: cid,
    projectId: pid,
    task,
    steps,
    model: projectStore.selectedModel,
    signal: abortCtrl.signal,
    onText: (chunk) => {
      convStore.appendChunk(aiMsg.id, chunk);
    },
    onEvent: ({ event, data }) => {
      if (event === "plan_step_start") {
        try {
          const d = JSON.parse(data);
          traceStore.startPlanStep(d.step);
        } catch {}
      } else if (event === "plan_step_done") {
        try {
          const d = JSON.parse(data);
          traceStore.donePlanStep(d.step);
        } catch {}
      } else if (event === "tool_call") {
        handleToolCall(data);
      } else if (event === "tool_result") {
        handleToolResult(data);
      } else if (event === "progress") {
        traceStore.setProgress(data);
      } else if (event === "metrics") {
        try {
          const m = JSON.parse(data);
          const card = `\n\n---\n📊 **性能报告**：耗时 ${m.durationSec}s · 工具调用 ${m.toolCalls} 次 · 预估 ${m.estimatedTokens} tokens\n`;
          convStore.appendChunk(aiMsg.id, card);
        } catch {}
      } else {
        convStore.appendChunk(aiMsg.id, data || "");
      }
    },
    onDone: () => {
      if (runningToolId) {
        traceStore.completeToolCall(runningToolId, {
          result: "(stream ended)",
        });
        runningToolId = null;
      }
      traceStore.setProgress(""); // 清空进度提示
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      convStore.fetchConversations();
      // 执行完成 → 刷新 diff badge
      diffStore.refreshBadge(cid, pid);
    },
    onError: (err) => {
      convStore.appendChunk(aiMsg.id, "\n\n[错误] " + err.message);
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      errorMsg.value = "执行失败：" + err.message;
    },
  });
}

function handlePlanCancel() {
  traceStore.clearPlan();
  convStore.setSending(false);
}

/** 降级：不走计划直接执行（旧逻辑） */
async function executeDirectly(cid, pid, text) {
  const aiMsg = convStore.createStreamingAssistantMessage();
  convStore.setSending(true);
  abortCtrl = new AbortController();

  await agentExecuteStream({
    sessionId: cid,
    conversationId: cid,
    projectId: pid,
    task: text,
    model: projectStore.selectedModel,
    signal: abortCtrl.signal,
    onText: (chunk) => {
      convStore.appendChunk(aiMsg.id, chunk);
      detectToolKeyword(chunk);
    },
    onEvent: ({ event, data }) => {
      if (event === "tool_call") {
        handleToolCall(data);
      } else if (event === "tool_result") {
        handleToolResult(data);
      } else if (event === "progress") {
        // 进度提示仅在 ToolTracePanel 中显示，不追加到消息文本
        traceStore.setProgress(data);
      } else if (event === "metrics") {
        try {
          const m = JSON.parse(data);
          const card = `\n\n---\n📊 **性能报告**：耗时 ${m.durationSec}s · 工具调用 ${m.toolCalls} 次 · 预估 ${m.estimatedTokens} tokens\n`;
          convStore.appendChunk(aiMsg.id, card);
        } catch {}
      } else {
        convStore.appendChunk(aiMsg.id, data || "");
      }
    },
    onDone: () => {
      if (runningToolId) {
        traceStore.completeToolCall(runningToolId, {
          result: "(stream ended)",
        });
        runningToolId = null;
      }
      traceStore.setProgress(""); // 清空进度提示
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      convStore.fetchConversations();
      // 执行完成 → 刷新 diff badge
      diffStore.refreshBadge(cid, pid);
    },
    onError: (err) => {
      convStore.appendChunk(aiMsg.id, "\n\n[错误] " + err.message);
      convStore.finishStreaming(aiMsg.id);
      convStore.setSending(false);
      errorMsg.value = "请求失败：" + err.message;
    },
  });
}

function handleToolCall(raw) {
  let payload = raw;
  try {
    payload = JSON.parse(raw);
  } catch {
    /* 文本原样 */
  }
  const tool = payload?.tool || payload?.name || "工具";
  const params =
    typeof payload === "object"
      ? JSON.stringify(
          payload?.params ?? payload?.arguments ?? payload,
          null,
          2,
        )
      : String(payload);
  runningToolId = traceStore.addToolCall({ tool, params });
}

function handleToolResult(raw) {
  let payload = raw;
  try {
    payload = JSON.parse(raw);
  } catch {
    /* 文本原样 */
  }
  const result =
    typeof payload === "object"
      ? JSON.stringify(payload?.result ?? payload, null, 2)
      : String(payload);
  if (runningToolId) {
    traceStore.completeToolCall(runningToolId, { result });
    runningToolId = null;
  } else {
    traceStore.appendToolResult({ tool: payload?.tool || "结果", result });
  }
}

// 降级：识别常见工具关键词
const KEYWORD_TOOLS = [
  "FileTool",
  "ShellTool",
  "RAGSearchTool",
  "FileReadTool",
  "FileWriteTool",
];
function detectToolKeyword(chunk) {
  for (const kw of KEYWORD_TOOLS) {
    if (chunk.includes(kw) && !runningToolId) {
      runningToolId = traceStore.addToolCall({
        tool: kw,
        params: "(从文本提取，降级模式)",
      });
    }
  }
}

function handleCancel() {
  abortCtrl?.abort();
}
</script>

<style scoped>
.pid-chip {
  font-size: 12px;
  background: rgba(59, 130, 246, 0.1);
  color: var(--blue);
  padding: 4px 10px;
  border-radius: 6px;
  font-family: "JetBrains Mono", monospace;
  border: 1px solid rgba(59, 130, 246, 0.2);
}
.diff-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.diff-badge {
  background: var(--red);
  color: #fff;
  font-size: 10px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 10px;
  font-weight: 600;
}
.btn-sm {
  padding: 4px 10px;
  font-size: 12px;
}
.warn-bar {
  background: rgba(245, 158, 11, 0.08);
  color: var(--amber);
  border-top: 1px solid rgba(245, 158, 11, 0.2);
  padding: 8px 20px;
  font-size: 12px;
}
.warn-bar a {
  color: var(--accent);
  text-decoration: underline;
}
.err-bar {
  background: rgba(239, 68, 68, 0.1);
  color: var(--red);
  border-top: 1px solid rgba(239, 68, 68, 0.25);
  padding: 8px 20px;
  font-size: 12px;
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
