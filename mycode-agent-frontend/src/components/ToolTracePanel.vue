<template>
  <!-- 移动端遮罩层 -->
  <div v-if="mobileOpen" class="trace-mask" @click="$emit('close')"></div>

  <aside class="trace-panel" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div class="tp-head">
      <div v-if="!collapsed" class="tp-tabs">
        <button
          class="tab"
          :class="{ active: tab === 'trace' }"
          @click="tab = 'trace'"
        >
          轨迹
          <span v-if="experimental" class="tag">实验</span>
        </button>
        <button
          class="tab"
          :class="{ active: tab === 'tree' }"
          @click="tab = 'tree'"
        >
          文件
        </button>
        <button
          class="tab"
          :class="{ active: tab === 'diff' }"
          @click="tab = 'diff'"
        >
          差异
        </button>
      </div>
      <div class="tp-actions" v-if="!collapsed && tab === 'trace'">
        <button
          class="btn btn-ghost btn-sm"
          @click="traceStore.clear"
          title="清空"
        >
          清空
        </button>
      </div>
      <button class="btn btn-ghost toggle" @click="$emit('toggle')">
        {{ collapsed ? "«" : "»" }}
      </button>
    </div>

    <div v-if="!collapsed" class="tp-body">
      <!-- 实时进度提示（始终显示在最顶部，不受标签切换影响） -->
      <div v-if="traceStore.currentProgress" class="trace-progress">
        {{ traceStore.currentProgress }}
      </div>
      <!-- 轨迹 -->
      <div v-show="tab === 'trace'" class="trace-list">
        <div
          v-if="!traceStore.traces.length && !traceStore.currentProgress"
          class="tp-empty"
        >
          暂无工具调用记录
        </div>
        <div
          v-for="item in traceStore.traces"
          :key="item.id"
          class="trace-item"
          :class="item.status"
        >
          <div class="trace-top">
            <span class="tool">{{ item.tool }}</span>
            <span class="status">
              {{ item.status === "running" ? "执行中…" : "已完成" }}
            </span>
            <span class="time">{{ formatTime(item.time) }}</span>
          </div>
          <div v-if="item.params" class="trace-section">
            <div class="lbl">参数</div>
            <pre>{{ truncate(item.params) }}</pre>
          </div>
          <div v-if="item.result" class="trace-section">
            <div class="lbl">返回</div>
            <pre>{{ truncate(item.result) }}</pre>
          </div>
        </div>
      </div>

      <!-- 文件树 -->
      <div v-show="tab === 'tree'" class="tab-pane">
        <FileTreePanel
          ref="treeRef"
          :session-id="sessionId"
          :project-id="projectId"
          @view-diff="onViewDiff"
        />
      </div>

      <!-- 差异 -->
      <div v-show="tab === 'diff'" class="tab-pane">
        <DiffView
          :session-id="sessionId"
          :project-id="projectId"
          :relative-path="diffPath"
          @restore-original="onRestoreOriginal"
        />
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { useAgentTraceStore } from "@/stores/agentTrace";
import { useConversationStore } from "@/stores/conversation";
import { useProjectStore } from "@/stores/project";
import FileTreePanel from "./FileTreePanel.vue";
import DiffView from "./DiffView.vue";
import { restoreFile } from "@/api/files";

defineProps({
  collapsed: { type: Boolean, default: false },
  experimental: { type: Boolean, default: false },
  mobileOpen: { type: Boolean, default: false },
});
defineEmits(["toggle", "close"]);

const traceStore = useAgentTraceStore();
const convStore = useConversationStore();
const projectStore = useProjectStore();

const tab = ref("trace");
const diffPath = ref("");
const treeRef = ref(null);

const sessionId = computed(() => convStore.activeId || "");
const projectId = computed(() => projectStore.projectId || "");

function onViewDiff(path) {
  diffPath.value = path;
  tab.value = "diff";
}

async function onRestoreOriginal() {
  if (!diffPath.value) return;
  if (!confirm(`确认用原项目原始版本覆盖 "${diffPath.value}"？`)) return;
  try {
    await restoreFile(sessionId.value, projectId.value, diffPath.value, "");
    treeRef.value?.reload();
    // 触发 DiffView 重新加载
    const p = diffPath.value;
    diffPath.value = "";
    setTimeout(() => (diffPath.value = p), 0);
  } catch (e) {
    alert("恢复失败: " + (e.message || e));
  }
}

// 工具调用刚完成后自动刷新文件树（如果当前在文件 Tab）
watch(
  () => traceStore.traces.map((t) => t.status).join(","),
  () => {
    if (tab.value === "tree") treeRef.value?.reload();
  },
);

function formatTime(ts) {
  const d = new Date(ts);
  return d.toLocaleTimeString("zh-CN", { hour12: false });
}
function truncate(s, max = 400) {
  if (!s) return "";
  return s.length > max ? s.slice(0, max) + "…" : s;
}
</script>

<style scoped>
.trace-panel {
  width: var(--trace-w);
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-left: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}
.trace-panel.collapsed {
  width: 36px;
}

/* 移动端遮罩 */
.trace-mask {
  display: none;
}

/* 移动端 overlay 模式 */
@media (max-width: 768px) {
  .trace-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: 85vw;
    max-width: 360px;
    z-index: 500;
    transform: translateX(100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: -4px 0 24px rgba(0, 0, 0, 0.5);
    border-left: 1px solid var(--border-default);
  }
  .trace-panel.mobile-open {
    transform: translateX(0);
  }
  .trace-panel.collapsed {
    display: none;
  }
  .trace-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(2px);
    z-index: 499;
  }
}
.tp-head {
  height: var(--header-h);
  padding: 0 6px 0 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
}
.tp-tabs {
  display: flex;
  gap: 2px;
  flex: 1;
  overflow-x: auto;
}
.tab {
  border: none;
  background: transparent;
  padding: 6px 10px;
  font-size: 12px;
  cursor: pointer;
  color: var(--text-secondary);
  border-bottom: 2px solid transparent;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: color 0.15s;
}
.tab:hover {
  color: var(--text-primary);
}
.tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
  font-weight: 600;
}
.tag {
  font-size: 9px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--amber-soft);
  color: var(--amber);
  font-weight: 400;
}
.btn-sm {
  padding: 2px 8px;
  font-size: 12px;
}
.toggle {
  padding: 4px 8px;
  font-size: 14px;
}

.tp-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.trace-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px;
}
.tab-pane {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.tp-empty {
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
  padding: 30px 0;
}
.trace-progress {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--accent);
  background: var(--accent-soft);
  border-bottom: 1px solid var(--border-subtle);
  animation: pulse 1.5s ease-in-out infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.6;
  }
}

.trace-item {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 8px 10px;
  margin-bottom: 8px;
  background: rgba(255, 255, 255, 0.02);
}
.trace-item.running {
  border-left: 3px solid var(--accent);
}
.trace-item.done {
  border-left: 3px solid var(--green);
}
.trace-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.tool {
  font-weight: 600;
  font-size: 13px;
  color: var(--accent);
}
.status {
  font-size: 11px;
  color: var(--text-secondary);
}
.time {
  font-size: 11px;
  color: var(--text-muted);
}
.trace-section {
  margin-top: 4px;
}
.lbl {
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 2px;
}
.trace-section pre {
  margin: 0;
  padding: 6px 8px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-family: "JetBrains Mono", monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow-y: auto;
  color: var(--text-primary);
}
</style>
