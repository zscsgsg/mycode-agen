<template>
  <div
    v-if="store.visible"
    class="diff-drawer-mask"
    @click.self="store.close()"
  >
    <div class="diff-drawer">
      <div class="diff-head">
        <div class="diff-title">
          工作区差异
          <span class="diff-sub" v-if="store.entries.length">
            （{{ store.entries.length }} 个文件待同步到原项目）
          </span>
        </div>
        <div class="diff-actions">
          <button
            class="btn btn-ghost btn-sm"
            :class="{ active: viewMode === 'side' }"
            @click="viewMode = viewMode === 'side' ? 'unified' : 'side'"
            :title="viewMode === 'side' ? '切换为统一视图' : '切换为并排对比'"
          >
            {{ viewMode === "side" ? "📖 并排对比" : "📄 统一视图" }}
          </button>
          <button
            class="btn btn-ghost btn-sm"
            :disabled="!store.entries.length || store.applying"
            @click="onDiscard"
            title="清空本次会话改动记录（沙箱文件保留）"
          >
            丢弃
          </button>
          <button
            class="btn btn-primary btn-sm"
            :disabled="!store.entries.length || store.applying"
            @click="onApply"
          >
            {{ store.applying ? "应用中..." : "应用到原项目" }}
          </button>
          <button class="btn btn-ghost btn-sm" @click="store.close()">
            关闭
          </button>
        </div>
      </div>

      <div v-if="store.error" class="diff-err">{{ store.error }}</div>

      <div class="diff-body">
        <div class="file-list">
          <div v-if="store.loading" class="file-empty">加载中...</div>
          <div v-else-if="!store.entries.length" class="file-empty">
            本次会话暂无改动
          </div>
          <div
            v-for="e in store.entries"
            :key="e.path"
            class="file-row"
            :class="{ active: e.path === store.activePath }"
            @click="store.setActive(e.path)"
            :title="e.path"
          >
            <span class="status-tag" :class="statusClass(e.status)">
              {{ statusLabel(e.status) }}
            </span>
            <span class="file-path">{{ e.path }}</span>
            <span class="file-stats">
              <span class="add">+{{ e.additions }}</span>
              <span class="del">-{{ e.deletions }}</span>
            </span>
          </div>
        </div>

        <div class="diff-view" v-if="viewMode === 'unified'">
          <pre v-if="activeEntry" class="diff-pre"><code><template
            v-for="(line, idx) in diffLines"
            :key="idx"
          ><span :class="lineClass(line)">{{ line }}</span>
</template></code></pre>
          <div v-else class="file-empty">选择左侧文件查看差异</div>
        </div>

        <div class="diff-view diff-split" v-else>
          <div v-if="!activeEntry" class="file-empty">选择左侧文件查看差异</div>
          <template v-else>
            <div class="split-header">
              <span class="split-label split-old">原项目（修改前）</span>
              <span class="split-label split-new">工作区（修改后）</span>
            </div>
            <div class="split-body">
              <div class="split-col split-left">
                <pre class="diff-pre"><code><template
                  v-for="(line, idx) in splitOldLines"
                  :key="idx"
                ><span :class="line.class">{{ line.text }}</span>
</template></code></pre>
              </div>
              <div class="split-col split-right">
                <pre class="diff-pre"><code><template
                  v-for="(line, idx) in splitNewLines"
                  :key="idx"
                ><span :class="line.class">{{ line.text }}</span>
</template></code></pre>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from "vue";
import { useWorkspaceDiffStore } from "@/stores/workspaceDiff";
import { useProjectStore } from "@/stores/project";
import { useConversationStore } from "@/stores/conversation";

const store = useWorkspaceDiffStore();
const projectStore = useProjectStore();
const convStore = useConversationStore();

const viewMode = ref("side"); // 'unified' | 'side'

const activeEntry = computed(() => store.activeEntry);

const diffLines = computed(() => {
  const text = activeEntry.value?.unifiedDiff || "";
  if (!text) return [];
  return text.split(/\r?\n/);
});

function statusLabel(s) {
  if (s === "CREATED") return "新增";
  if (s === "MODIFIED") return "修改";
  return "未变";
}
function statusClass(s) {
  if (s === "CREATED") return "tag-add";
  if (s === "MODIFIED") return "tag-mod";
  return "tag-none";
}
function lineClass(line) {
  if (!line) return "diff-line";
  if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@"))
    return "diff-line diff-meta";
  if (line.startsWith("+")) return "diff-line diff-add";
  if (line.startsWith("-")) return "diff-line diff-del";
  return "diff-line";
}

// 并排对比：从 unified diff 解析出 old/new 两侧的行
const splitOldLines = computed(() => {
  const lines = diffLines.value;
  const result = [];
  for (const line of lines) {
    if (line.startsWith("+++") || line.startsWith("---")) continue;
    if (line.startsWith("@@")) {
      result.push({ text: line, class: "diff-line diff-meta" });
    } else if (line.startsWith("+")) {
      // 新增行在左侧显示为空占位
      result.push({ text: "", class: "diff-line diff-empty" });
    } else if (line.startsWith("-")) {
      result.push({ text: line, class: "diff-line diff-del" });
    } else {
      result.push({ text: line, class: "diff-line" });
    }
  }
  return result;
});

const splitNewLines = computed(() => {
  const lines = diffLines.value;
  const result = [];
  for (const line of lines) {
    if (line.startsWith("+++") || line.startsWith("---")) continue;
    if (line.startsWith("@@")) {
      result.push({ text: line, class: "diff-line diff-meta" });
    } else if (line.startsWith("-")) {
      // 删除行在右侧显示为空占位
      result.push({ text: "", class: "diff-line diff-empty" });
    } else if (line.startsWith("+")) {
      result.push({ text: line, class: "diff-line diff-add" });
    } else {
      result.push({ text: line, class: "diff-line" });
    }
  }
  return result;
});

async function onApply() {
  const sessionId = convStore.activeId;
  const projectId = projectStore.projectId;
  if (!sessionId || !projectId) return;
  if (!confirm("确认把这些改动写入原项目并重建索引？该操作不可撤销。")) return;
  try {
    const n = await store.apply(sessionId, projectId);
    alert(`已应用 ${n} 个文件，向量索引已重建。`);
  } catch (e) {
    /* error 已挂在 store.error */
  }
}

async function onDiscard() {
  const sessionId = convStore.activeId;
  if (!sessionId) return;
  if (!confirm("确认丢弃本次会话所有改动记录？沙箱文件保留。")) return;
  try {
    await store.discard(sessionId);
  } catch {}
}
</script>

<style scoped>
.diff-drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.diff-drawer {
  width: min(1100px, 92vw);
  height: 100%;
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 32px rgba(0, 0, 0, 0.5);
  border-left: 1px solid var(--border-default);
}
.diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-glass);
  flex-shrink: 0;
}
.diff-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.diff-sub {
  color: var(--text-secondary);
  font-weight: 400;
  font-size: 13px;
}
.diff-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.btn-sm {
  padding: 4px 12px;
  font-size: 12px;
}
.diff-err {
  background: rgba(239, 68, 68, 0.1);
  color: var(--red);
  padding: 8px 16px;
  font-size: 12px;
  border-bottom: 1px solid rgba(239, 68, 68, 0.25);
}

.diff-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.file-list {
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-subtle);
  overflow-y: auto;
  background: rgba(0, 0, 0, 0.15);
}
.file-empty {
  padding: 24px 16px;
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12.5px;
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-secondary);
}
.file-row.active {
  background: var(--accent-soft);
  color: var(--text-primary);
}
.file-row:hover {
  background: rgba(255, 255, 255, 0.04);
}
.file-row.active:hover {
  background: rgba(34, 211, 160, 0.15);
}
.status-tag {
  flex-shrink: 0;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
}
.tag-add {
  background: var(--green-soft);
  color: var(--green);
}
.tag-mod {
  background: var(--amber-soft);
  color: var(--amber);
}
.tag-none {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
}
.file-path {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: "JetBrains Mono", monospace;
}
.file-stats {
  display: flex;
  gap: 6px;
  font-size: 11px;
  font-family: "JetBrains Mono", monospace;
}
.file-stats .add {
  color: var(--green);
}
.file-stats .del {
  color: var(--red);
}

.diff-view {
  flex: 1;
  overflow: auto;
  background: var(--bg-primary);
}
.diff-pre {
  margin: 0;
  padding: 12px 16px;
  font-family: "JetBrains Mono", monospace;
  font-size: 12.5px;
  line-height: 1.55;
  white-space: pre;
  color: var(--text-primary);
}
.diff-line {
  display: block;
}
.diff-add {
  background: rgba(34, 197, 94, 0.1);
  color: var(--green);
}
.diff-del {
  background: rgba(239, 68, 68, 0.1);
  color: var(--red);
}
.diff-meta {
  color: var(--text-muted);
}

/* 并排对比视图 */
.diff-split {
  display: flex;
  flex-direction: column;
}
.split-header {
  display: flex;
  border-bottom: 1px solid var(--border-subtle);
  background: rgba(0, 0, 0, 0.2);
}
.split-label {
  flex: 1;
  padding: 6px 16px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
}
.split-label.split-old {
  border-right: 1px solid var(--border-subtle);
  color: var(--red);
}
.split-label.split-new {
  color: var(--green);
}
.split-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.split-col {
  flex: 1;
  overflow: auto;
}
.split-col.split-left {
  border-right: 1px solid var(--border-subtle);
}
.diff-empty {
  background: rgba(255, 255, 255, 0.02);
  color: var(--text-muted);
}
.btn-ghost.active {
  background: var(--accent-soft);
  color: var(--accent);
}

/* 移动端全屏 */
@media (max-width: 768px) {
  .diff-drawer-mask {
    justify-content: center;
    align-items: stretch;
  }
  .diff-drawer {
    width: 100vw;
    height: 100%;
  }
  .diff-head {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start;
    padding: 10px 12px;
  }
  .diff-actions {
    width: 100%;
    justify-content: flex-end;
    gap: 6px;
  }
  .diff-body {
    flex-direction: column;
  }
  .file-list {
    width: 100%;
    max-height: 35vh;
    border-right: none;
    border-bottom: 1px solid var(--border-subtle);
  }
  .file-row {
    padding: 6px 10px;
    font-size: 12px;
  }
  .diff-view {
    flex: 1;
  }
  .diff-pre {
    padding: 8px 10px;
    font-size: 11px;
  }
  .split-label {
    padding: 4px 8px;
    font-size: 11px;
  }
}
</style>
