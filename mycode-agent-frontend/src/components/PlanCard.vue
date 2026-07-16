<template>
  <div class="plan-card" v-if="traceStore.planStatus !== 'idle'">
    <div class="plan-header" @click="toggleCollapse">
      <span class="plan-icon">&#128203;</span>
      <span class="plan-title">任务计划</span>
      <span class="plan-summary" v-if="collapsed">
        {{ collapsedSummary }}
      </span>
      <span class="plan-badge" :class="traceStore.planStatus">
        {{ statusLabel }}
      </span>
      <span class="collapse-arrow" :class="{ collapsed }">&#9660;</span>
    </div>

    <div v-show="!collapsed" class="plan-body">
      <!-- 生成中 -->
      <div v-if="traceStore.planStatus === 'generating'" class="plan-loading">
        正在分析任务并生成执行计划...
      </div>

      <!-- 步骤列表 -->
      <div v-else class="plan-steps">
        <div
          v-for="(step, idx) in traceStore.planSteps"
          :key="step.step"
          class="step-row"
          :class="step.status"
        >
          <div class="step-num">
            <span v-if="step.status === 'done'" class="check">&#10003;</span>
            <span v-else-if="step.status === 'running'" class="spinner"></span>
            <span v-else class="num">{{ step.step }}</span>
          </div>
          <div class="step-content">
            <div class="step-title">
              <span class="type-tag">{{ step.type }}</span>
              {{ step.title }}
            </div>
            <div class="step-desc" v-if="!editing">{{ step.description }}</div>
            <textarea
              v-else
              v-model="editableSteps[idx].description"
              class="step-edit"
              rows="2"
            />
          </div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="plan-actions" v-if="traceStore.planStatus === 'ready'">
        <button class="btn btn-primary" @click.stop="$emit('confirm')">
          确认执行
        </button>
        <button class="btn btn-ghost" @click.stop="toggleEdit">
          {{ editing ? "完成编辑" : "编辑计划" }}
        </button>
        <button class="btn btn-ghost btn-danger" @click.stop="$emit('cancel')">
          取消
        </button>
      </div>

      <!-- 执行中 / 完成 -->
      <div class="plan-footer" v-if="traceStore.planStatus === 'executing'">
        <span class="progress-text">
          执行中：{{ doneCount }} / {{ traceStore.planSteps.length }} 步
        </span>
      </div>
      <div
        class="plan-footer plan-done"
        v-if="traceStore.planStatus === 'done'"
      >
        全部步骤执行完成
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { useAgentTraceStore } from "@/stores/agentTrace";

const emit = defineEmits(["confirm", "cancel"]);
const traceStore = useAgentTraceStore();

const editing = ref(false);
const editableSteps = ref([]);
const collapsed = ref(false);

const statusLabel = computed(() => {
  const m = {
    generating: "生成中",
    ready: "待确认",
    executing: "执行中",
    done: "已完成",
  };
  return m[traceStore.planStatus] || "";
});

const doneCount = computed(
  () => traceStore.planSteps.filter((s) => s.status === "done").length,
);

const collapsedSummary = computed(() => {
  const total = traceStore.planSteps.length;
  const done = doneCount.value;
  if (traceStore.planStatus === "done") return `${total} 步全部完成`;
  if (traceStore.planStatus === "executing") return `${done}/${total} 步`;
  return `${total} 步`;
});

function toggleCollapse() {
  // 待确认状态不允许收起（避免用户找不到按钮）
  if (traceStore.planStatus === "ready") return;
  collapsed.value = !collapsed.value;
}

function toggleEdit() {
  if (!editing.value) {
    editableSteps.value = traceStore.planSteps.map((s) => ({ ...s }));
    editing.value = true;
  } else {
    traceStore.updatePlanSteps(editableSteps.value);
    editing.value = false;
  }
}

watch(
  () => traceStore.planStatus,
  (s) => {
    if (s === "executing") editing.value = false;
    // 执行完成后自动收起，减少占用空间
    if (s === "done") collapsed.value = true;
    // 新计划生成时展开
    if (s === "generating" || s === "ready") collapsed.value = false;
  },
);
</script>

<style scoped>
.plan-card {
  background: var(--bg-glass);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 14px 16px;
  margin: 10px 0;
}
.plan-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 0;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}
.plan-header:hover {
  opacity: 0.8;
}
.plan-icon {
  font-size: 18px;
}
.plan-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
}
.plan-summary {
  font-size: 12px;
  color: var(--text-secondary);
}
.collapse-arrow {
  font-size: 10px;
  color: var(--text-muted);
  transition: transform 0.2s;
  margin-left: 4px;
}
.collapse-arrow.collapsed {
  transform: rotate(-90deg);
}
.plan-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  margin-left: auto;
  font-weight: 500;
}
.plan-badge.generating {
  background: var(--amber-soft);
  color: var(--amber);
}
.plan-badge.ready {
  background: var(--blue-soft);
  color: var(--blue);
}
.plan-badge.executing {
  background: var(--accent-soft);
  color: var(--accent);
}
.plan-badge.done {
  background: var(--green-soft);
  color: var(--green);
}
.plan-loading {
  font-size: 13px;
  color: var(--text-secondary);
  padding: 12px 0;
  text-align: center;
}
.plan-body {
  margin-top: 10px;
}

.plan-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step-row {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.step-row.running {
  background: var(--blue-soft);
  border-color: rgba(59, 130, 246, 0.3);
}
.step-row.done {
  opacity: 0.7;
}
.step-num {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.step-row.pending .step-num {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
}
.step-row.running .step-num {
  background: var(--blue);
  color: white;
}
.step-row.done .step-num {
  background: var(--green);
  color: white;
}
.check {
  font-size: 14px;
}
.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.num {
  font-size: 11px;
}
.step-content {
  flex: 1;
  min-width: 0;
}
.step-title {
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-primary);
}
.type-tag {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-secondary);
  font-family: "JetBrains Mono", monospace;
}
.step-desc {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.step-edit {
  width: 100%;
  font-size: 12px;
  margin-top: 4px;
  border: 1px solid var(--border-default);
  border-radius: 4px;
  padding: 4px 6px;
  resize: vertical;
  background: var(--bg-elevated);
  color: var(--text-primary);
}

.plan-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.btn {
  padding: 6px 14px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: none;
}
.btn-primary {
  background: var(--accent);
  color: #070c1b;
  font-weight: 600;
}
.btn-primary:hover {
  background: var(--accent-hover);
}
.btn-ghost {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-default);
}
.btn-ghost:hover {
  background: rgba(255, 255, 255, 0.04);
}
.btn-danger {
  color: var(--red);
  border-color: rgba(239, 68, 68, 0.3);
}
.plan-footer {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 10px;
  text-align: center;
}
.plan-done {
  color: var(--green);
  font-weight: 500;
}
</style>
