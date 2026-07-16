<template>
  <div class="input-box">
    <ModelSelector
      v-if="showModel"
      :model-value="modelValue"
      @update:model-value="$emit('update:modelValue', $event)"
    />
    <textarea
      ref="taRef"
      v-model="text"
      :placeholder="placeholder"
      :disabled="disabled"
      rows="1"
      @keydown="onKeyDown"
      @input="autoResize"
    />
    <div class="actions">
      <button
        v-if="disabled && cancellable"
        class="btn btn-danger"
        @click="$emit('cancel')"
      >
        停止
      </button>
      <button
        class="btn btn-primary"
        :disabled="disabled || !text.trim()"
        @click="handleSend"
      >
        <span v-if="disabled">生成中…</span>
        <span v-else>发送 ↵</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from "vue";
import ModelSelector from "./ModelSelector.vue";

const props = defineProps({
  placeholder: {
    type: String,
    default: "输入内容，Enter 发送，Shift+Enter 换行",
  },
  disabled: { type: Boolean, default: false },
  cancellable: { type: Boolean, default: false },
  showModel: { type: Boolean, default: false },
  modelValue: { type: String, default: "qwen-plus" },
});
const emit = defineEmits(["send", "cancel", "update:modelValue"]);

const text = ref("");
const taRef = ref(null);

onMounted(() => autoResize());

function autoResize() {
  const ta = taRef.value;
  if (!ta) return;
  ta.style.height = "auto";
  ta.style.height = Math.min(ta.scrollHeight, 200) + "px";
}

function handleSend() {
  const val = text.value.trim();
  if (!val || props.disabled) return;
  emit("send", val);
  text.value = "";
  nextTick(autoResize);
}

function onKeyDown(e) {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    handleSend();
  }
}
</script>

<style scoped>
.input-box {
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  padding: 12px 20px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  flex-wrap: wrap;
}
textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.5;
  outline: none;
  background: var(--bg-elevated);
  color: var(--text-primary);
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
  max-height: 200px;
}
textarea::placeholder {
  color: var(--text-muted);
}
textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-glow);
}
textarea:disabled {
  background: rgba(255, 255, 255, 0.03);
  opacity: 0.6;
}
.actions {
  display: flex;
  gap: 6px;
  align-items: flex-end;
  padding-bottom: 2px;
}

/* 移动端 */
@media (max-width: 768px) {
  .input-box {
    padding: 8px 10px;
    gap: 6px;
    padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
  }
  textarea {
    font-size: 14px;
    padding: 8px 10px;
    min-height: 40px;
  }
  .actions {
    gap: 4px;
  }
  .actions .btn {
    font-size: 13px;
    padding: 8px 14px;
  }
}
</style>
