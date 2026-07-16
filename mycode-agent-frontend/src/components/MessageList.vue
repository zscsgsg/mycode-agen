<template>
  <div ref="listRef" class="msg-list">
    <div v-if="!messages.length" class="empty">
      <div class="empty-icon">💬</div>
      <div>{{ emptyText }}</div>
    </div>

    <div
      v-for="msg in messages"
      :key="msg.id"
      class="msg-row"
      :class="msg.role"
    >
      <div class="avatar" :class="msg.role">
        {{ msg.role === "user" ? "我" : "AI" }}
      </div>
      <div class="bubble" :class="[msg.role, { streaming: msg.streaming }]">
        <div
          v-if="msg.role === 'assistant'"
          class="content md-content"
          v-html="renderMd(msg.content)"
        ></div>
        <div v-else class="content">
          <template v-for="(tok, i) in tokenize(msg.content)" :key="i">
            <span v-if="tok.type === 'text'" class="seg">{{ tok.value }}</span>
            <button
              v-else
              class="cite-chip"
              :title="`点击查看 ${tok.path} 第 ${tok.start}${tok.end ? '-' + tok.end : ''} 行`"
              @click="handleCiteClick(tok)"
            >
              📎 {{ shortFile(tok.path) }}#L{{ tok.start
              }}<span v-if="tok.end">-L{{ tok.end }}</span>
            </button>
          </template>
        </div>
        <span v-if="msg.streaming" class="cursor-blink"></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from "vue";
import { marked } from "marked";
import { useRetrievedDocsStore } from "@/stores/retrievedDocs";

// 配置 marked
marked.setOptions({
  breaks: true,
  gfm: true,
});

const props = defineProps({
  messages: { type: Array, required: true },
  emptyText: { type: String, default: "开始你的第一条消息吧" },
});

const listRef = ref(null);
const retrievedStore = useRetrievedDocsStore();

// 引用格式：[相对路径#L起] 或 [相对路径#L起-L止] 或 [相对路径#L起-止]
// 例：[src/main/java/Student.java#L10-L25]
const CITE_RE = /\[([^\]\n\r]+?)#L(\d+)(?:-L?(\d+))?\]/g;

function tokenize(content) {
  const text = String(content || "");
  if (!text) return [];
  const tokens = [];
  let lastIdx = 0;
  CITE_RE.lastIndex = 0;
  let m;
  while ((m = CITE_RE.exec(text)) !== null) {
    if (m.index > lastIdx) {
      tokens.push({ type: "text", value: text.slice(lastIdx, m.index) });
    }
    tokens.push({
      type: "cite",
      path: m[1].trim(),
      start: m[2],
      end: m[3] || "",
    });
    lastIdx = m.index + m[0].length;
  }
  if (lastIdx < text.length) {
    tokens.push({ type: "text", value: text.slice(lastIdx) });
  }
  return tokens;
}

function shortFile(p) {
  if (!p) return "";
  const parts = p.split(/[/\\]/);
  return parts[parts.length - 1] || p;
}

/** 渲染 Markdown（AI 消息专用） */
function renderMd(content) {
  if (!content) return "";
  // 移除 citation 标记，避免显示原始标记
  const cleaned = content.replace(/\[[^\]\n\r]+?#L\d+(?:-L?\d+)?\]/g, "");
  return marked.parse(cleaned);
}

function handleCiteClick(tok) {
  const doc = retrievedStore.findByLocation(tok.path, tok.start);
  if (doc) {
    retrievedStore.highlight(doc.id);
  } else {
    // 没找到精确匹配的片段，仍然展开右侧任何可能的同文件片段
    const fallback = retrievedStore.docs.find(
      (d) =>
        d.file_path === tok.path ||
        (d.file_path || "").endsWith(tok.path) ||
        tok.path.endsWith(d.file_path || ""),
    );
    if (fallback) retrievedStore.highlight(fallback.id);
  }
}

function scrollToBottom() {
  const el = listRef.value;
  if (!el) return;
  el.scrollTop = el.scrollHeight;
}

onMounted(() => scrollToBottom());

watch(
  () => props.messages.map((m) => m.content.length).join(","),
  () => nextTick(scrollToBottom),
);
watch(
  () => props.messages.length,
  () => nextTick(scrollToBottom),
);
</script>

<style scoped>
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  gap: 10px;
  font-size: 13.5px;
}
.empty-icon {
  font-size: 44px;
  opacity: 0.5;
}

.msg-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 100%;
  animation: fadeInUp 0.3s ease;
}
.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
  letter-spacing: 0.02em;
}
.avatar.user {
  background: var(--user-bubble-bg);
  color: #fff;
}
.avatar.assistant {
  background: rgba(34, 211, 160, 0.12);
  color: var(--accent);
  border: 1px solid rgba(34, 211, 160, 0.2);
}

.bubble {
  max-width: 76%;
  border-radius: var(--radius-lg);
  padding: 10px 14px;
  word-break: break-word;
}
.bubble.user {
  background: var(--user-bubble-bg);
  color: var(--user-bubble-text);
  border-top-right-radius: 4px;
  box-shadow: 0 2px 12px rgba(124, 58, 237, 0.25);
}
.bubble.assistant {
  background: var(--ai-bubble-bg);
  color: var(--text-primary);
  border-top-left-radius: 4px;
  border: 1px solid var(--ai-bubble-border);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.bubble.streaming {
  border-left: 2px solid var(--accent);
}
.content {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.seg {
  white-space: pre-wrap;
}
.cite-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin: 0 2px;
  padding: 1px 6px;
  background: rgba(34, 211, 160, 0.1);
  color: var(--accent);
  border: 1px solid rgba(34, 211, 160, 0.25);
  border-radius: 4px;
  font-size: 12px;
  font-family: "JetBrains Mono", monospace;
  cursor: pointer;
  vertical-align: baseline;
  line-height: 1.4;
  white-space: nowrap;
  transition:
    background 0.15s,
    border-color 0.15s;
}
.cite-chip:hover {
  background: rgba(34, 211, 160, 0.18);
  border-color: var(--accent);
}
.bubble.user .cite-chip {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.4);
}
.cursor-blink {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: currentColor;
  margin-left: 2px;
  animation: blink 1s steps(2, start) infinite;
  vertical-align: text-bottom;
}
@keyframes blink {
  to {
    visibility: hidden;
  }
}

/* Markdown 渲染样式 */
.md-content {
  white-space: normal;
}
.md-content :deep(p) {
  margin: 0.4em 0;
}
.md-content :deep(p:first-child) {
  margin-top: 0;
}
.md-content :deep(p:last-child) {
  margin-bottom: 0;
}
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3) {
  margin: 0.6em 0 0.3em;
  font-weight: 600;
  line-height: 1.4;
}
.md-content :deep(h1) {
  font-size: 1.3em;
}
.md-content :deep(h2) {
  font-size: 1.15em;
}
.md-content :deep(h3) {
  font-size: 1.05em;
}
.md-content :deep(ul),
.md-content :deep(ol) {
  margin: 0.3em 0;
  padding-left: 1.6em;
}
.md-content :deep(li) {
  margin: 0.15em 0;
}
.md-content :deep(code) {
  background: rgba(255, 255, 255, 0.08);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: "JetBrains Mono", monospace;
  font-size: 0.88em;
  color: var(--accent);
}
.md-content :deep(pre) {
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid var(--border-subtle);
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 0.5em 0;
}
.md-content :deep(pre code) {
  background: none;
  padding: 0;
  color: var(--text-primary);
  font-size: 0.85em;
}
.md-content :deep(blockquote) {
  border-left: 3px solid var(--accent);
  margin: 0.5em 0;
  padding: 4px 12px;
  color: var(--text-secondary);
  background: rgba(34, 211, 160, 0.05);
  border-radius: 0 4px 4px 0;
}
.md-content :deep(table) {
  border-collapse: collapse;
  margin: 0.5em 0;
  font-size: 0.9em;
  width: 100%;
}
.md-content :deep(th),
.md-content :deep(td) {
  border: 1px solid var(--border-subtle);
  padding: 4px 8px;
  text-align: left;
}
.md-content :deep(th) {
  background: rgba(255, 255, 255, 0.05);
  font-weight: 600;
}
.md-content :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-subtle);
  margin: 0.8em 0;
}
.md-content :deep(strong) {
  color: var(--text-primary);
  font-weight: 600;
}
.md-content :deep(em) {
  color: var(--text-secondary);
}

/* 移动端 */
@media (max-width: 768px) {
  .msg-list {
    padding: 12px 10px;
    gap: 12px;
  }
  .msg-row {
    gap: 6px;
  }
  .avatar {
    width: 28px;
    height: 28px;
    font-size: 10px;
    border-radius: 6px;
  }
  .bubble {
    max-width: 84%;
    padding: 8px 10px;
    border-radius: var(--radius-md);
  }
  .content {
    font-size: 13px;
    line-height: 1.6;
  }
  .md-content :deep(pre) {
    padding: 8px 10px;
    font-size: 0.8em;
  }
  .md-content :deep(table) {
    font-size: 0.8em;
    display: block;
    overflow-x: auto;
  }
}
</style>
