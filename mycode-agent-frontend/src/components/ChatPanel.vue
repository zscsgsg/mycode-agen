<template>
  <div class="chat-panel">
    <div class="chat-head">
      <div class="title-box">
        <div class="title">{{ title }}</div>
        <div class="subtitle">{{ subtitle }}</div>
      </div>
      <div class="head-right">
        <slot name="head-right" />
      </div>
    </div>

    <MessageList :messages="messages" :empty-text="emptyText" />

    <slot name="after-messages" />

    <slot name="notice" />

    <InputBox
      :placeholder="placeholder"
      :disabled="sending"
      :cancellable="cancellable"
      :show-model="showModel"
      :model-value="modelValue"
      @update:model-value="$emit('update:modelValue', $event)"
      @send="$emit('send', $event)"
      @cancel="$emit('cancel')"
    />
  </div>
</template>

<script setup>
import MessageList from "./MessageList.vue";
import InputBox from "./InputBox.vue";

defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: "" },
  messages: { type: Array, required: true },
  sending: { type: Boolean, default: false },
  placeholder: { type: String, default: "" },
  emptyText: { type: String, default: "" },
  cancellable: { type: Boolean, default: true },
  showModel: { type: Boolean, default: false },
  modelValue: { type: String, default: "qwen-plus" },
});

defineEmits(["send", "cancel", "update:modelValue"]);
</script>

<style scoped>
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-primary);
}
.chat-head {
  height: var(--header-h);
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
.title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.subtitle {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 移动端 */
@media (max-width: 768px) {
  .chat-head {
    padding: 0 10px;
    height: var(--header-h);
  }
  .title {
    font-size: 13px;
  }
  .subtitle {
    font-size: 11px;
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .head-right {
    gap: 4px;
  }
  .head-right .btn {
    font-size: 11px;
    padding: 4px 8px;
  }
}
</style>
