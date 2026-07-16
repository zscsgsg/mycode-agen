<template>
  <!-- 移动端遮罩层 -->
  <div v-if="mobileOpen" class="sidebar-mask" @click="$emit('close')"></div>

  <aside class="sidebar" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div class="sb-header">
      <div class="brand" v-if="!collapsed">
        <div class="brand-badge">C</div>
        <div>
          <div class="brand-title">CodeMate</div>
          <div class="brand-sub">AI 编程辅助</div>
        </div>
      </div>
      <button
        class="btn btn-ghost toggle"
        @click="$emit('toggle')"
        :title="collapsed ? '展开' : '折叠'"
      >
        {{ collapsed ? "»" : "«" }}
      </button>
    </div>

    <div v-if="!collapsed" class="sb-body">
      <!-- 仅在 assistant 页面显示上传区 -->
      <FileUpload v-if="mode === 'assistant'" />

      <!-- 会话列表（两个页面都有） -->
      <ConversationList />
    </div>

    <div v-if="!collapsed" class="sb-footer">
      <button class="btn mode-btn" @click="switchMode">
        切换到
        {{ mode === "assistant" ? "编程智能体 (ReAct)" : "编程助手 (RAG)" }}
      </button>
      <router-link to="/" class="btn home-btn">返回首页</router-link>
    </div>
  </aside>
</template>

<script setup>
import { useRouter } from "vue-router";
import { useConversationStore } from "@/stores/conversation";
import FileUpload from "./FileUpload.vue";
import ConversationList from "./ConversationList.vue";

defineProps({
  collapsed: { type: Boolean, default: false },
  mode: { type: String, required: true }, // 'assistant' | 'agent'
  mobileOpen: { type: Boolean, default: false },
});
defineEmits(["toggle", "close"]);

const router = useRouter();
const convStore = useConversationStore();

function switchMode() {
  const next = convStore.mode === "assistant" ? "agent" : "assistant";
  convStore.setMode(next);
  router.push(next === "assistant" ? "/assistant" : "/agent");
}
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-w);
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-right: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar.collapsed {
  width: 48px;
}

/* 移动端遮罩 */
.sidebar-mask {
  display: none;
}

/* 移动端 overlay 模式 */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 85vw;
    max-width: 320px;
    z-index: 500;
    transform: translateX(-100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.5);
    border-right: 1px solid var(--border-default);
  }
  .sidebar.mobile-open {
    transform: translateX(0);
  }
  .sidebar.collapsed {
    display: none;
  }
  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(2px);
    z-index: 499;
  }
}

.sb-header {
  height: var(--header-h);
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.brand-badge {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--accent) 0%, var(--blue) 100%);
  color: #070c1b;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 16px;
}
.brand-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.brand-sub {
  font-size: 11px;
  color: var(--text-secondary);
}
.toggle {
  padding: 4px 8px;
  font-size: 14px;
}

.sb-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sb-footer {
  padding: 10px 12px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mode-btn {
  width: 100%;
  justify-content: center;
}
.home-btn {
  width: 100%;
  text-decoration: none;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
