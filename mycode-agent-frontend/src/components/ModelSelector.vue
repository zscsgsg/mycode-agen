<template>
  <div class="model-selector" ref="wrapperRef">
    <button
      class="model-btn"
      @click="toggleDropdown"
      :title="'当前模型：' + currentLabel"
    >
      <span class="model-icon">🤖</span>
      <span class="model-name">{{ currentLabel }}</span>
      <span class="model-arrow" :class="{ open: showDropdown }">▾</span>
    </button>

    <Transition name="fade">
      <div v-if="showDropdown" class="model-dropdown">
        <div
          v-for="m in models"
          :key="m.value"
          class="model-item"
          :class="{ active: m.value === modelValue }"
          @click="selectModel(m.value)"
        >
          <span class="item-icon">{{ m.icon }}</span>
          <div class="item-info">
            <span class="item-label">{{ m.label }}</span>
            <span class="item-desc">{{ m.desc }}</span>
          </div>
          <span v-if="m.value === modelValue" class="item-check">✓</span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from "vue";

const props = defineProps({
  modelValue: { type: String, default: "qwen-plus" },
});
const emit = defineEmits(["update:modelValue"]);

const models = [
  {
    value: "qwen-plus",
    label: "Qwen-Plus",
    icon: "⚡",
    desc: "均衡性能，适合大多数任务",
  },
  {
    value: "qwen-max3.7",
    label: "Qwen-Max",
    icon: "🧠",
    desc: "最强推理，复杂任务首选",
  },
  {
    value: "deepseek-v4-pro",
    label: "DeepSeek-V4 Pro",
    icon: "🧠",
    desc: "最强编程推理，复杂任务首选",
  },
  {
    value: "deepseek-v4-flash",
    label: "DeepSeek-V4 Flash",
    icon: "⚡",
    desc: "快速经济，日常任务优选",
  },
];

const currentLabel = computed(() => {
  const m = models.find((m) => m.value === props.modelValue);
  return m ? m.label : props.modelValue;
});

const showDropdown = ref(false);
const wrapperRef = ref(null);

function toggleDropdown() {
  showDropdown.value = !showDropdown.value;
}

function selectModel(value) {
  emit("update:modelValue", value);
  showDropdown.value = false;
}

function onClickOutside(e) {
  if (wrapperRef.value && !wrapperRef.value.contains(e.target)) {
    showDropdown.value = false;
  }
}

onMounted(() => document.addEventListener("click", onClickOutside));
onBeforeUnmount(() => document.removeEventListener("click", onClickOutside));
</script>

<style scoped>
.model-selector {
  position: relative;
  display: inline-flex;
}

.model-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
  color: var(--text-primary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.model-btn:hover {
  border-color: var(--accent);
  background: rgba(59, 130, 246, 0.06);
}

.model-icon {
  font-size: 14px;
}

.model-name {
  font-weight: 500;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-arrow {
  font-size: 10px;
  transition: transform 0.2s;
  color: var(--text-muted);
}
.model-arrow.open {
  transform: rotate(180deg);
}

.model-dropdown {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  min-width: 260px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
  padding: 4px;
  z-index: 100;
}

.model-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.15s;
}
.model-item:hover {
  background: rgba(59, 130, 246, 0.08);
}
.model-item.active {
  background: rgba(59, 130, 246, 0.12);
}

.item-icon {
  font-size: 18px;
  width: 24px;
  text-align: center;
  flex-shrink: 0;
}

.item-info {
  flex: 1;
  min-width: 0;
}

.item-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.item-desc {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 1px;
}

.item-check {
  color: var(--accent);
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}

/* Transition */
.fade-enter-active,
.fade-leave-active {
  transition:
    opacity 0.15s,
    transform 0.15s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
