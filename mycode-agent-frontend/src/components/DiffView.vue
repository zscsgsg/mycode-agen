<template>
  <div class="diff-view">
    <div v-if="!relativePath" class="empty">在文件树中选择一个文件查看差异</div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty err">{{ error }}</div>
    <template v-else>
      <div class="meta">
        <span class="path" :title="relativePath">{{ relativePath }}</span>
        <span class="badges">
          <span v-if="!data.originalExists" class="badge new">新增</span>
          <span v-else-if="!data.workspaceExists" class="badge del"
            >已删除</span
          >
          <span v-else-if="data.unifiedDiff" class="badge mod">已修改</span>
          <span v-else class="badge same">未变更</span>
        </span>
        <button
          class="btn btn-ghost btn-sm"
          v-if="data.originalExists"
          @click="$emit('restore-original')"
          title="用原项目原始版本覆盖工作区"
        >
          恢复原始版
        </button>
      </div>
      <pre class="diff" v-if="data.unifiedDiff"><span
        v-for="(line, i) in lines"
        :key="i"
        :class="lineClass(line)"
      >{{ line }}<br/></span></pre>
      <div v-else class="empty">两端内容一致，无差异</div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { fetchDiff } from "@/api/files";

const props = defineProps({
  sessionId: { type: String, default: "" },
  projectId: { type: String, default: "" },
  relativePath: { type: String, default: "" },
});
defineEmits(["restore-original"]);

const data = ref({});
const loading = ref(false);
const error = ref("");

const lines = computed(() => {
  if (!data.value?.unifiedDiff) return [];
  return data.value.unifiedDiff.split("\n");
});

function lineClass(line) {
  if (line.startsWith("+++") || line.startsWith("---")) return "header";
  if (line.startsWith("+")) return "add";
  if (line.startsWith("-")) return "del";
  if (line.startsWith("@@")) return "hunk";
  return "ctx";
}

async function load() {
  if (!props.sessionId || !props.projectId || !props.relativePath) return;
  loading.value = true;
  error.value = "";
  try {
    data.value = await fetchDiff(
      props.sessionId,
      props.projectId,
      props.relativePath,
    );
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.sessionId, props.projectId, props.relativePath],
  () => load(),
  { immediate: true },
);

defineExpose({ reload: load });
</script>

<style scoped>
.diff-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--bg-primary);
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
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 12px;
  color: var(--text-primary);
}
.path {
  flex: 1;
  font-family: "JetBrains Mono", monospace;
  font-size: 12px;
  word-break: break-all;
}
.badges {
  display: flex;
  gap: 4px;
}
.badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border-subtle);
}
.badge.new {
  background: var(--green-soft);
  color: var(--green);
}
.badge.del {
  background: var(--red-soft);
  color: var(--red);
}
.badge.mod {
  background: var(--amber-soft);
  color: var(--amber);
}
.badge.same {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-muted);
}
.btn-sm {
  padding: 2px 8px;
  font-size: 11px;
}
.diff {
  flex: 1;
  overflow: auto;
  margin: 0;
  padding: 8px 10px;
  background: var(--bg-primary);
  font-size: 12px;
  font-family: "JetBrains Mono", monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-primary);
}
.diff .header {
  color: var(--text-muted);
}
.diff .hunk {
  color: var(--blue);
  background: var(--blue-soft);
}
.diff .add {
  background: var(--green-soft);
  color: var(--green);
}
.diff .del {
  background: var(--red-soft);
  color: var(--red);
}
.diff .ctx {
  color: var(--text-secondary);
}
</style>
