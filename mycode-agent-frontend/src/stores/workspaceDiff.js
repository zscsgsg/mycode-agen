import { defineStore } from "pinia";
import {
  fetchWorkspaceDiff,
  applyWorkspaceDiff,
  discardWorkspaceDiff,
} from "@/api/agentDiff";

/**
 * 工作区 diff 状态：用于 Agent 模式右上角「同步到原项目」按钮 + 抽屉。
 */
export const useWorkspaceDiffStore = defineStore("workspaceDiff", {
  state: () => ({
    visible: false,
    loading: false,
    applying: false,
    entries: [], // [{ path, status, additions, deletions, unifiedDiff, oldContent, newContent }]
    activePath: "", // 当前在抽屉中查看的文件
    badgeCount: 0, // 未应用的改动文件数（按钮上的小红点）
    error: "",
    lastAppliedAt: 0,
  }),
  getters: {
    activeEntry: (state) =>
      state.entries.find((e) => e.path === state.activePath) || null,
  },
  actions: {
    async refreshBadge(sessionId, projectId) {
      if (!sessionId || !projectId) {
        this.badgeCount = 0;
        return;
      }
      try {
        const list = await fetchWorkspaceDiff(sessionId, projectId);
        this.badgeCount = Array.isArray(list) ? list.length : 0;
      } catch (e) {
        // 静默失败，不阻塞主流程
        this.badgeCount = 0;
      }
    },
    async open(sessionId, projectId) {
      this.visible = true;
      this.error = "";
      this.loading = true;
      try {
        const list = await fetchWorkspaceDiff(sessionId, projectId);
        this.entries = Array.isArray(list) ? list : [];
        this.badgeCount = this.entries.length;
        this.activePath = this.entries[0]?.path || "";
      } catch (e) {
        this.error = e.message || "拉取 diff 失败";
        this.entries = [];
      } finally {
        this.loading = false;
      }
    },
    close() {
      this.visible = false;
    },
    setActive(path) {
      this.activePath = path;
    },
    async apply(sessionId, projectId) {
      this.applying = true;
      this.error = "";
      try {
        const n = await applyWorkspaceDiff(sessionId, projectId);
        this.entries = [];
        this.badgeCount = 0;
        this.activePath = "";
        this.lastAppliedAt = Date.now();
        this.visible = false;
        return n;
      } catch (e) {
        this.error = e.message || "应用 diff 失败";
        throw e;
      } finally {
        this.applying = false;
      }
    },
    async discard(sessionId) {
      this.error = "";
      try {
        await discardWorkspaceDiff(sessionId);
        this.entries = [];
        this.badgeCount = 0;
        this.activePath = "";
        this.visible = false;
      } catch (e) {
        this.error = e.message || "丢弃 diff 失败";
        throw e;
      }
    },
  },
});
