<template>
  <div class="ftp">
    <div class="ftp-toolbar">
      <button class="btn btn-ghost btn-sm" @click="load" title="刷新">
        ↻ 刷新
      </button>
      <span class="legend">
        <span class="dot mod"></span>已修改 <span class="dot new"></span>新增
      </span>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty err">{{ error }}</div>
    <div v-else-if="!root || !root.children?.length" class="empty">
      工作区为空，AI 还没在这里创建/修改文件
    </div>
    <div v-else class="ftp-tree">
      <TreeNode
        v-for="child in root.children"
        :key="child.path"
        :node="child"
        :depth="0"
        :selected-path="selectedPath"
        @select="onSelect"
      />
    </div>

    <div v-if="selectedNode && !selectedNode.directory" class="ftp-actions">
      <span class="sel-path" :title="selectedPath">{{ selectedPath }}</span>
      <button
        v-if="selectedNode.status !== 'unchanged'"
        class="btn btn-ghost btn-sm"
        @click="$emit('view-diff', selectedPath)"
      >
        看差异
      </button>
      <button
        v-if="selectedNode.status === 'modified'"
        class="btn btn-ghost btn-sm danger"
        @click="onRestore"
      >
        撤销修改
      </button>
    </div>
  </div>
</template>

<script setup>
import { defineComponent, h, ref, watch } from "vue";
import { fetchTree, restoreFile } from "@/api/files";

const props = defineProps({
  sessionId: { type: String, default: "" },
  projectId: { type: String, default: "" },
});
const emit = defineEmits(["view-diff"]);

const root = ref(null);
const loading = ref(false);
const error = ref("");
const selectedPath = ref("");
const selectedNode = ref(null);

async function load() {
  if (!props.sessionId) return;
  loading.value = true;
  error.value = "";
  try {
    root.value = await fetchTree(props.sessionId, props.projectId);
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function onSelect(node) {
  selectedPath.value = node.path;
  selectedNode.value = node;
  if (!node.directory) emit("view-diff", node.path);
}

async function onRestore() {
  if (!selectedNode.value || selectedNode.value.directory) return;
  if (
    !confirm(
      `确认把 "${selectedPath.value}" 恢复到原项目原始版本？此操作不可逆（但当前内容会先备份）。`,
    )
  )
    return;
  try {
    await restoreFile(props.sessionId, props.projectId, selectedPath.value, "");
    await load();
    emit("view-diff", selectedPath.value);
  } catch (e) {
    alert("恢复失败: " + (e.message || e));
  }
}

watch(
  () => [props.sessionId, props.projectId],
  () => load(),
  { immediate: true },
);
defineExpose({ reload: load });

// 递归节点组件（同文件内定义，避免再开一个 .vue）
const TreeNode = defineComponent({
  name: "TreeNode",
  props: {
    node: { type: Object, required: true },
    depth: { type: Number, default: 0 },
    selectedPath: { type: String, default: "" },
  },
  emits: ["select"],
  setup(p, { emit: nodeEmit }) {
    const expanded = ref(p.depth < 1);
    return () => {
      const indent = { paddingLeft: 8 + p.depth * 12 + "px" };
      const isSelected = p.selectedPath === p.node.path;
      const cls = ["row"];
      if (isSelected) cls.push("selected");
      if (!p.node.directory) cls.push("file", `s-${p.node.status}`);
      const icon = p.node.directory ? (expanded.value ? "▾" : "▸") : "";
      const fileIcon = p.node.directory ? "📁" : iconForStatus(p.node.status);
      const row = h(
        "div",
        {
          class: cls,
          style: indent,
          onClick: () => {
            if (p.node.directory) expanded.value = !expanded.value;
            nodeEmit("select", p.node);
          },
        },
        [
          h("span", { class: "caret" }, icon),
          h("span", { class: "ic" }, fileIcon),
          h("span", { class: "nm" }, p.node.name),
        ],
      );
      const children =
        p.node.directory && expanded.value && p.node.children?.length
          ? p.node.children.map((c) =>
              h(TreeNode, {
                key: c.path,
                node: c,
                depth: p.depth + 1,
                selectedPath: p.selectedPath,
                onSelect: (n) => nodeEmit("select", n),
              }),
            )
          : [];
      return h("div", null, [row, ...children]);
    };
  },
});

function iconForStatus(s) {
  if (s === "modified") return "✎";
  if (s === "new") return "✚";
  return "📄";
}
</script>

<style scoped>
.ftp {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.ftp-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 11px;
}
.legend {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 2px;
  vertical-align: middle;
}
.dot.mod {
  background: var(--amber);
}
.dot.new {
  background: var(--green);
}
.btn-sm {
  padding: 2px 8px;
  font-size: 11px;
}
.btn-sm.danger {
  color: var(--red);
}
.empty {
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
  padding: 20px 12px;
}
.empty.err {
  color: var(--red);
}
.ftp-tree {
  flex: 1;
  overflow: auto;
  padding: 4px 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 0;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  color: var(--text-secondary);
}
.row:hover {
  background: rgba(255, 255, 255, 0.04);
}
.row.selected {
  background: var(--accent-soft);
  color: var(--text-primary);
}
.row .caret {
  width: 10px;
  display: inline-block;
  color: var(--text-muted);
}
.row .ic {
  width: 14px;
  display: inline-block;
  text-align: center;
  font-size: 11px;
}
.row.file.s-modified .nm {
  color: var(--amber);
  font-weight: 600;
}
.row.file.s-new .nm {
  color: var(--green);
  font-weight: 600;
}
.ftp-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-top: 1px solid var(--border-subtle);
  font-size: 11px;
  background: rgba(0, 0, 0, 0.15);
}
.sel-path {
  flex: 1;
  font-family: "JetBrains Mono", monospace;
  word-break: break-all;
  color: var(--text-primary);
}
</style>
