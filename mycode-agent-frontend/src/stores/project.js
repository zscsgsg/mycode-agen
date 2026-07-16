import { defineStore } from "pinia";
import { ref } from "vue";
import { uuid } from "@/utils/uuid";

/**
 * 项目信息 store：管理 projectId 与已上传文件列表
 * 持久化到 localStorage
 */
export const useProjectStore = defineStore(
  "project",
  () => {
    const projectId = ref("");
    const files = ref([]); // [{ name, size, uploadedAt }]
    // 当前选中的模型（持久化到 localStorage）
    const selectedModel = ref("qwen-plus");
    // 索引更新时间戳与最近一次智能体改动的文件列表
    // 当 Agent 写/改文件后由 agentTrace 触发，用于 Assistant 顶部 banner 联动
    const vectorsUpdatedAt = ref(0);
    const recentChangedFiles = ref([]); // [string] 相对路径

    function markVectorsUpdated(changedFiles) {
      vectorsUpdatedAt.value = Date.now();
      if (Array.isArray(changedFiles) && changedFiles.length) {
        // 去重保留最近 10 个
        const set = new Set([...changedFiles, ...recentChangedFiles.value]);
        recentChangedFiles.value = Array.from(set).slice(0, 10);
      }
    }

    function clearVectorsBanner() {
      vectorsUpdatedAt.value = 0;
      recentChangedFiles.value = [];
    }

    function ensureProjectId() {
      if (!projectId.value) {
        projectId.value = uuid();
      }
      return projectId.value;
    }

    function resetProject() {
      projectId.value = uuid();
      files.value = [];
    }

    function addFiles(list) {
      const now = Date.now();
      for (const item of list) {
        const file = item?.file || item;
        if (!file) continue;
        const path =
          item?.path ||
          file.webkitRelativePath ||
          file.relativePath ||
          file.name;
        files.value.push({
          name: path,
          size: file.size,
          uploadedAt: now,
        });
      }
    }

    function clearFiles() {
      files.value = [];
    }

    return {
      projectId,
      files,
      selectedModel,
      vectorsUpdatedAt,
      recentChangedFiles,
      ensureProjectId,
      resetProject,
      addFiles,
      clearFiles,
      markVectorsUpdated,
      clearVectorsBanner,
    };
  },
  {
    persist: {
      key: "codemate-project",
      storage: localStorage,
    },
  },
);
