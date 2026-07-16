<template>
  <!-- 移动端遮罩层 -->
  <div v-if="mobileOpen" class="rd-mask" @click="$emit('close')"></div>

  <aside class="rd-panel" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div class="rd-head">
      <div v-if="!collapsed" class="rd-title">
        <span class="ic">🔍</span>
        <span>检索片段</span>
        <span v-if="store.total > 0" class="badge">{{ store.total }}</span>
      </div>
      <button class="btn btn-ghost toggle" @click="$emit('toggle')">
        {{ collapsed ? "«" : "»" }}
      </button>
    </div>

    <div v-if="!collapsed" class="rd-body">
      <div v-if="!store.total" class="rd-empty">
        提问后此处会显示本次检索到的代码片段
      </div>

      <div v-else class="rd-summary">
        本次检索到 <b>{{ store.total }}</b> 个片段
        <span class="sub"
          >（向量 {{ store.vectorHits }} / BM25 {{ store.bm25Hits }}，已 RRF
          融合）</span
        >
      </div>

      <div class="rd-list">
        <div
          v-for="(d, idx) in store.docs"
          :key="d.id"
          class="rd-item"
          :class="{ highlight: store.highlightId === d.id }"
          :ref="(el) => bindItemRef(el, d.id)"
        >
          <div class="rd-row1" @click="store.toggle(d.id)">
            <span class="rd-idx">#{{ idx + 1 }}</span>
            <span class="rd-file" :title="d.file_path">{{
              shortPath(d.file_path)
            }}</span>
            <span v-if="d.start_line" class="rd-lines"
              >L{{ d.start_line }}-{{ d.end_line }}</span
            >
            <span class="branch" :class="(d.branch || 'VECTOR').toLowerCase()">
              {{ branchLabel(d.branch) }}
            </span>
          </div>
          <div class="rd-row2">
            <span class="rd-tag">{{ d.tag || "text" }}</span>
            <span v-if="d.class_name" class="rd-class">{{ d.class_name }}</span>
            <span v-if="d.method_name" class="rd-method"
              >{{ d.method_name }}()</span
            >
            <span v-if="d.rrf_score" class="rd-score"
              >RRF {{ Number(d.rrf_score).toFixed(4) }}</span
            >
          </div>
          <pre class="rd-snippet">{{
            store.expandedIds.has(d.id) ? d.full_text : d.snippet
          }}</pre>
          <div
            v-if="d.full_text && d.full_text.length > (d.snippet || '').length"
            class="rd-more"
            @click="store.toggle(d.id)"
          >
            {{ store.expandedIds.has(d.id) ? "收起" : "展开完整片段" }}
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { useRetrievedDocsStore } from "@/stores/retrievedDocs";
import { watch, nextTick } from "vue";

defineProps({
  collapsed: { type: Boolean, default: false },
  mobileOpen: { type: Boolean, default: false },
});
defineEmits(["toggle", "close"]);

const store = useRetrievedDocsStore();
const itemRefs = new Map();

function bindItemRef(el, id) {
  if (el) itemRefs.set(id, el);
}

function shortPath(p) {
  if (!p) return "";
  const parts = p.split(/[/\\]/);
  if (parts.length <= 2) return p;
  return ".../" + parts.slice(-2).join("/");
}

function branchLabel(b) {
  if (b === "BOTH") return "向量+BM25";
  if (b === "BM25") return "BM25";
  return "向量";
}

// 点击引用 chip 触发高亮时，自动滚动到对应片段
watch(
  () => store.highlightId,
  async (id) => {
    if (!id) return;
    await nextTick();
    const el = itemRefs.get(id);
    if (el) el.scrollIntoView({ behavior: "smooth", block: "center" });
  },
);
</script>

<style scoped>
.rd-panel {
  width: var(--trace-w, 360px);
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
.rd-panel.collapsed {
  width: 36px;
}
.rd-head {
  height: var(--header-h);
  padding: 0 6px 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
}
.rd-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.ic {
  font-size: 14px;
}
.badge {
  background: var(--accent);
  color: #070c1b;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
  font-weight: 600;
}
.toggle {
  padding: 4px 8px;
  font-size: 14px;
}
.rd-body {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px 16px;
}
.rd-empty {
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
  padding: 30px 8px;
}
.rd-summary {
  font-size: 12px;
  color: var(--text-primary);
  background: var(--blue-soft);
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  margin-bottom: 10px;
  border: 1px solid rgba(59, 130, 246, 0.2);
}
.rd-summary .sub {
  color: var(--text-secondary);
  margin-left: 4px;
}
.rd-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rd-item {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
  padding: 8px 10px;
  transition:
    background 0.25s,
    border-color 0.25s;
}
.rd-item.highlight {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: 0 0 0 2px var(--accent-glow);
}
.rd-row1 {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 12px;
  flex-wrap: wrap;
}
.rd-idx {
  color: var(--text-secondary);
  font-weight: 600;
}
.rd-file {
  color: var(--accent);
  font-weight: 600;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rd-lines {
  color: var(--text-secondary);
  font-size: 11px;
  font-family: "JetBrains Mono", monospace;
}
.branch {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  font-weight: 500;
}
.branch.vector {
  background: var(--purple-soft);
  color: var(--purple);
}
.branch.bm25 {
  background: var(--green-soft);
  color: var(--green);
}
.branch.both {
  background: var(--amber-soft);
  color: var(--amber);
}
.rd-row2 {
  display: flex;
  gap: 6px;
  margin-top: 4px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--text-secondary);
}
.rd-tag {
  background: rgba(255, 255, 255, 0.06);
  padding: 0 5px;
  border-radius: 3px;
  color: var(--text-secondary);
}
.rd-class {
  color: var(--blue);
}
.rd-method {
  color: var(--green);
}
.rd-score {
  font-family: "JetBrains Mono", monospace;
  color: var(--text-muted);
}
.rd-snippet {
  margin: 6px 0 0;
  padding: 6px 8px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-family: "JetBrains Mono", Consolas, monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow-y: auto;
  color: var(--text-primary);
}
.rd-more {
  font-size: 11px;
  color: var(--accent);
  cursor: pointer;
  margin-top: 4px;
  text-align: right;
}
.rd-more:hover {
  text-decoration: underline;
}

/* 移动端遮罩 */
.rd-mask {
  display: none;
}

/* 移动端 overlay 模式 */
@media (max-width: 768px) {
  .rd-panel {
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
  .rd-panel.mobile-open {
    transform: translateX(0);
  }
  .rd-panel.collapsed {
    display: none;
  }
  .rd-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(2px);
    z-index: 499;
  }
}
</style>
