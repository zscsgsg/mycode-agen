<template>
  <div class="conv-list">
    <div class="conv-head">
      <span>
        会话
        <span class="mode-tag">{{
          convStore.mode === "assistant" ? "RAG" : "ReAct"
        }}</span>
      </span>
      <div class="head-actions">
        <button
          class="btn btn-ghost btn-sm"
          :disabled="convStore.listLoading"
          @click="handleRefresh"
          title="刷新列表"
        >
          {{ convStore.listLoading ? "加载中…" : "↻" }}
        </button>
        <button class="btn btn-primary btn-sm" @click="handleCreate">
          + 新建
        </button>
      </div>
    </div>

    <div
      v-if="convStore.listError"
      class="err-tip"
      :title="convStore.listError"
    >
      加载失败：{{ convStore.listError }}
    </div>

    <div
      v-if="convStore.listLoading && !convStore.conversations.length"
      class="empty-tip"
    >
      正在加载会话…
    </div>
    <div v-else-if="!convStore.conversations.length" class="empty-tip">
      暂无会话，点击“新建”开始对话
    </div>

    <ul class="list">
      <li
        v-for="conv in convStore.conversations"
        :key="conv.id"
        class="conv-item"
        :class="{ active: conv.id === convStore.activeId }"
        @click="handleSwitch(conv.id)"
      >
        <input
          v-if="editingId === conv.id"
          ref="editInputRef"
          v-model="editingValue"
          class="conv-title-input"
          maxlength="60"
          @click.stop
          @keydown.enter.prevent="commitEdit(conv)"
          @keydown.esc.prevent="cancelEdit"
          @blur="commitEdit(conv)"
        />
        <div
          v-else
          class="conv-title"
          :title="convStore.getDisplayTitle(conv)"
          @dblclick.stop="startEdit(conv)"
        >
          {{ convStore.getDisplayTitle(conv) }}
        </div>
        <div class="conv-meta">
          <span class="conv-time">{{
            formatTime(conv.updateTime || conv.createTime)
          }}</span>
          <span class="actions">
            <button
              class="btn-icon"
              title="重命名"
              @click.stop="startEdit(conv)"
            >
              ✎
            </button>
            <button
              class="btn-icon"
              title="删除"
              @click.stop="handleDelete(conv.id)"
            >
              ✕
            </button>
          </span>
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from "vue";
import { useConversationStore } from "@/stores/conversation";

const convStore = useConversationStore();

// 编辑中的会话Id与输入值
const editingId = ref("");
const editingValue = ref("");
const editInputRef = ref(null);

function startEdit(conv) {
  if (!conv) return;
  editingId.value = conv.id;
  // 编辑时使用原始 title（若为空则预填显示标题）
  editingValue.value =
    (conv.title && String(conv.title).trim()) ||
    convStore.getDisplayTitle(conv);
  nextTick(() => {
    const el = Array.isArray(editInputRef.value)
      ? editInputRef.value[0]
      : editInputRef.value;
    el?.focus?.();
    el?.select?.();
  });
}

function cancelEdit() {
  editingId.value = "";
  editingValue.value = "";
}

async function commitEdit(conv) {
  if (!editingId.value || editingId.value !== conv.id) return;
  const id = editingId.value;
  const newTitle = editingValue.value.trim();
  editingId.value = "";
  editingValue.value = "";
  if (!newTitle) return;
  if (newTitle === conv.title) return;
  try {
    await convStore.renameConversation(id, newTitle);
  } catch (e) {
    alert("重命名失败：" + (e?.message || "请重试"));
  }
}

onMounted(() => {
  convStore.fetchConversations();
});

// 切换模式时重新拉取
watch(
  () => convStore.mode,
  () => {
    convStore.fetchConversations();
    // 切换模式后，若没有激活会话则清空消息
    if (!convStore.activeId) convStore.clearMessages();
  },
);

async function handleRefresh() {
  await convStore.fetchConversations();
}

async function handleCreate() {
  await convStore.createConversation(
    "新会话 " + new Date().toLocaleTimeString(),
  );
}

async function handleSwitch(id) {
  if (id === convStore.activeId) return;
  await convStore.switchConversation(id);
}

async function handleDelete(id) {
  if (!confirm("确定删除这个会话？")) return;
  await convStore.deleteConversation(id);
}

function formatTime(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  if (isNaN(d.getTime())) return "";
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) {
    return d.toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
    });
  }
  return d.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
}
</script>

<style scoped>
.conv-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 12px 12px 0;
}
.conv-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.head-actions {
  display: flex;
  gap: 4px;
}
.mode-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 1px 6px;
  font-size: 10px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 4px;
  font-weight: 600;
}
.err-tip {
  background: rgba(239, 68, 68, 0.1);
  color: var(--red);
  border: 1px solid rgba(239, 68, 68, 0.25);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  margin-bottom: 6px;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.btn-sm {
  padding: 4px 10px;
  font-size: 12px;
}
.empty-tip {
  padding: 16px 6px;
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
}
.conv-item {
  padding: 8px 10px;
  border-radius: var(--radius-md);
  margin-bottom: 4px;
  cursor: pointer;
  transition: background 0.15s;
}
.conv-item:hover {
  background: rgba(255, 255, 255, 0.04);
}
.conv-item.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.conv-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-title-input {
  width: 100%;
  font-size: 13px;
  padding: 2px 6px;
  border: 1px solid var(--accent);
  border-radius: 4px;
  outline: none;
  background: var(--bg-elevated);
  color: var(--text-primary);
}
.actions {
  display: inline-flex;
  gap: 2px;
}
.conv-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-secondary);
}
.conv-item.active .conv-meta {
  color: var(--accent);
}
.btn-icon {
  border: none;
  background: transparent;
  color: inherit;
  opacity: 0;
  padding: 0 4px;
  font-size: 12px;
}
.conv-item:hover .btn-icon {
  opacity: 0.7;
}
.btn-icon:hover {
  opacity: 1;
  color: var(--red);
}
</style>
