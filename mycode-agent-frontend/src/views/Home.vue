<template>
  <div class="home">
    <!-- 环境光斑 -->
    <div class="glow-orb top-left"></div>
    <div class="glow-orb bottom-right"></div>

    <div class="hero">
      <div class="logo-mark">
        <svg viewBox="0 0 56 56" class="logo-svg">
          <rect
            x="4"
            y="4"
            width="48"
            height="48"
            rx="14"
            fill="none"
            stroke="var(--accent)"
            stroke-width="2.5"
          />
          <text
            x="28"
            y="38"
            text-anchor="middle"
            fill="var(--accent)"
            font-size="28"
            font-weight="800"
            font-family="Inter, sans-serif"
          >
            &lt;/&gt;
          </text>
        </svg>
      </div>
      <h1>
        <span class="gradient-text">CodeMate</span>
      </h1>
      <p class="subtitle">AI 编程智能体 · 检索增强 + 自主执行</p>
      <div class="hero-stats">
        <div class="stat">
          <span class="stat-val">RAG</span>
          <span class="stat-lbl">检索增强</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-val">ReAct</span>
          <span class="stat-lbl">推理行动</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-val">Planner</span>
          <span class="stat-lbl">任务规划</span>
        </div>
      </div>
    </div>

    <div class="cards">
      <div class="mode-card assistant" @click="goAssistant">
        <div class="card-badge">RAG</div>
        <div class="card-icon">
          <svg viewBox="0 0 32 32" width="32" height="32">
            <circle
              cx="12"
              cy="12"
              r="8"
              fill="none"
              stroke="var(--blue)"
              stroke-width="2"
            />
            <circle
              cx="20"
              cy="20"
              r="6"
              fill="none"
              stroke="var(--purple)"
              stroke-width="2"
            />
            <line
              x1="18"
              y1="18"
              x2="14"
              y2="14"
              stroke="var(--text-muted)"
              stroke-width="1.5"
            />
          </svg>
        </div>
        <div class="card-title">编程助手</div>
        <p class="desc">
          上传代码仓库，基于混合检索（向量 +
          BM25）精准定位代码，回答项目相关问题。
        </p>
        <div class="card-features">
          <span>混合检索</span>
          <span>代码定位</span>
          <span>引用溯源</span>
        </div>
        <div class="card-action">进入 →</div>
      </div>

      <div class="mode-card agent" @click="goAgent">
        <div class="card-badge">ReAct</div>
        <div class="card-icon">
          <svg viewBox="0 0 32 32" width="32" height="32">
            <rect
              x="4"
              y="4"
              width="24"
              height="24"
              rx="5"
              fill="none"
              stroke="var(--accent)"
              stroke-width="2"
            />
            <circle cx="16" cy="14" r="3" fill="var(--accent)" opacity="0.6" />
            <path
              d="M10 22l4-4 3 2 5-5"
              fill="none"
              stroke="var(--accent)"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </div>
        <div class="card-title">编程智能体</div>
        <p class="desc">
          自主规划任务、调用工具链（文件读写 / Shell /
          检索），完成代码生成与修改。
        </p>
        <div class="card-features">
          <span>任务规划</span>
          <span>工具链</span>
          <span>工作区</span>
        </div>
        <div class="card-action">进入 →</div>
      </div>
    </div>

    <div class="footer-note">
      <span class="dot-live"></span>
      后端接口 :8082 · 前端 :5173
    </div>
  </div>
</template>

<script setup>
import { useRouter } from "vue-router";
import { useConversationStore } from "@/stores/conversation";

const router = useRouter();
const convStore = useConversationStore();

function goAssistant() {
  convStore.setMode("assistant");
  router.push("/assistant");
}
function goAgent() {
  convStore.setMode("agent");
  router.push("/agent");
}
</script>

<style scoped>
.home {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  position: relative;
  overflow: hidden;
  background: var(--bg-deep);
}

/* 环境光斑 */
.glow-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  z-index: 0;
}
.glow-orb.top-left {
  width: 420px;
  height: 420px;
  top: -120px;
  left: -80px;
  background: radial-gradient(
    circle,
    rgba(34, 211, 160, 0.13) 0%,
    transparent 70%
  );
}
.glow-orb.bottom-right {
  width: 360px;
  height: 360px;
  bottom: -80px;
  right: -60px;
  background: radial-gradient(
    circle,
    rgba(99, 102, 241, 0.1) 0%,
    transparent 70%
  );
}

.hero {
  text-align: center;
  margin-bottom: 52px;
  position: relative;
  z-index: 1;
}

.logo-mark {
  margin-bottom: 20px;
}
.logo-svg {
  width: 64px;
  height: 64px;
  filter: drop-shadow(0 0 24px var(--accent-glow));
}

h1 {
  margin: 0;
  font-size: 44px;
  font-weight: 800;
  letter-spacing: -1px;
  line-height: 1.2;
}
.gradient-text {
  background: linear-gradient(
    135deg,
    var(--accent) 0%,
    #60a5fa 50%,
    var(--purple) 100%
  );
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.subtitle {
  color: var(--text-secondary);
  font-size: 16px;
  margin-top: 12px;
  font-weight: 400;
  letter-spacing: 0.02em;
}

.hero-stats {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  margin-top: 28px;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 0 20px;
}
.stat-val {
  font-size: 14px;
  font-weight: 700;
  font-family: "JetBrains Mono", monospace;
  color: var(--accent);
  letter-spacing: 0.05em;
}
.stat-lbl {
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
}
.stat-divider {
  width: 1px;
  height: 24px;
  background: var(--border-default);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 24px;
  width: 100%;
  max-width: 900px;
  position: relative;
  z-index: 1;
}

.mode-card {
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 28px;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  overflow: hidden;
}
.mode-card::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: var(--radius-xl);
  opacity: 0;
  transition: opacity 0.3s ease;
  z-index: 0;
}
.mode-card.assistant::before {
  background: radial-gradient(
    ellipse at top left,
    rgba(59, 130, 246, 0.08) 0%,
    transparent 60%
  );
}
.mode-card.agent::before {
  background: radial-gradient(
    ellipse at top left,
    rgba(34, 211, 160, 0.08) 0%,
    transparent 60%
  );
}
.mode-card:hover::before {
  opacity: 1;
}
.mode-card:hover {
  transform: translateY(-4px);
  border-color: var(--border-strong);
  box-shadow: var(--shadow-lg);
}
.mode-card > * {
  position: relative;
  z-index: 1;
}

.card-badge {
  display: inline-block;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  padding: 3px 10px;
  border-radius: 20px;
  margin-bottom: 16px;
}
.mode-card.assistant .card-badge {
  background: var(--blue-soft);
  color: var(--blue);
  border: 1px solid rgba(59, 130, 246, 0.25);
}
.mode-card.agent .card-badge {
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid rgba(34, 211, 160, 0.25);
}

.card-icon {
  margin-bottom: 14px;
  opacity: 0.85;
}

.card-title {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 10px;
  color: var(--text-primary);
}

.desc {
  color: var(--text-secondary);
  font-size: 13.5px;
  line-height: 1.65;
  margin: 0 0 16px;
}

.card-features {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.card-features span {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-weight: 500;
}

.card-action {
  color: var(--accent);
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: gap 0.2s ease;
}
.mode-card:hover .card-action {
  gap: 10px;
}

.footer-note {
  margin-top: 48px;
  font-size: 12px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
  position: relative;
  z-index: 1;
}
.dot-live {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: glow-pulse 2s ease-in-out infinite;
}

/* 移动端 */
@media (max-width: 768px) {
  .home {
    padding: 24px 16px;
    justify-content: flex-start;
    padding-top: 60px;
  }
  .glow-orb.top-left {
    width: 200px;
    height: 200px;
    top: -60px;
    left: -40px;
  }
  .glow-orb.bottom-right {
    width: 180px;
    height: 180px;
    bottom: -40px;
    right: -30px;
  }
  .hero {
    margin-bottom: 32px;
  }
  .logo-svg {
    width: 48px;
    height: 48px;
  }
  h1 {
    font-size: 28px;
  }
  .subtitle {
    font-size: 13px;
    margin-top: 8px;
  }
  .hero-stats {
    margin-top: 20px;
    gap: 0;
  }
  .stat {
    padding: 0 12px;
  }
  .cards {
    grid-template-columns: 1fr;
    gap: 16px;
    max-width: 100%;
  }
  .mode-card {
    padding: 20px;
  }
  .card-title {
    font-size: 18px;
  }
  .desc {
    font-size: 12.5px;
  }
  .footer-note {
    margin-top: 32px;
    font-size: 11px;
  }
}
</style>
