import { defineStore } from "pinia";
import { ref } from "vue";
import { uuid } from "@/utils/uuid";
import { useProjectStore } from "@/stores/project";

// 写文件类工具：完成后前端认为后端已自动 updateFileVector，向 project store 推送提示
const FILE_WRITE_TOOLS = [
  "FileTool",
  "FileWriteTool",
  "EditFileTool",
  "WriteFileTool",
  "writeFile",
  "editFile",
  "applyPatch",
];
function extractChangedFile(params) {
  if (!params) return null;
  try {
    const obj = typeof params === "string" ? JSON.parse(params) : params;
    return obj?.path || obj?.filePath || obj?.relativePath || obj?.file || null;
  } catch {
    return null;
  }
}

/**
 * 工具调用轨迹 + 计划状态 store（仅用于 /agent 页面）
 * 数据来自后端 SSE 的 tool_call / tool_result / plan_step_start / plan_step_done 事件。
 */
export const useAgentTraceStore = defineStore("agentTrace", () => {
  // [{ id, tool, params, result, time, status: 'running'|'done' }]
  const traces = ref([]);

  // 计划状态
  // planStatus: 'idle' | 'generating' | 'ready' | 'executing' | 'done'
  const planStatus = ref("idle");
  // [{ step, title, description, type, status: 'pending'|'running'|'done' }]
  const planSteps = ref([]);
  const planTask = ref("");
  // 当前进度提示（来自后端 progress 事件）
  const currentProgress = ref("");

  function addToolCall({ tool, params }) {
    const item = {
      id: uuid(),
      tool: tool || "未知工具",
      params: params || "",
      result: "",
      status: "running",
      time: Date.now(),
    };
    traces.value.push(item);
    return item.id;
  }

  function completeToolCall(id, { result }) {
    const item = traces.value.find((t) => t.id === id);
    if (item) {
      item.result = result || "";
      item.status = "done";
      // 若是文件写入工具，触发 project 更新提示
      if (FILE_WRITE_TOOLS.includes(item.tool)) {
        const fp = extractChangedFile(item.params);
        try {
          useProjectStore().markVectorsUpdated(fp ? [fp] : []);
        } catch (_) {
          /* store 未初始化时忽略 */
        }
      }
    }
  }

  function appendToolResult({ tool, result }) {
    traces.value.push({
      id: uuid(),
      tool: tool || "结果",
      params: "",
      result: result || "",
      status: "done",
      time: Date.now(),
    });
  }

  // === Plan 相关 ===
  function setPlan(task, steps) {
    planTask.value = task;
    planSteps.value = steps.map((s) => ({ ...s, status: "pending" }));
    planStatus.value = "ready";
  }

  function setPlanGenerating(task) {
    planTask.value = task;
    planSteps.value = [];
    planStatus.value = "generating";
  }

  function startPlanExecution() {
    planStatus.value = "executing";
  }

  function startPlanStep(stepNum) {
    const s = planSteps.value.find((x) => x.step === stepNum);
    if (s) s.status = "running";
  }

  function donePlanStep(stepNum) {
    const s = planSteps.value.find((x) => x.step === stepNum);
    if (s) s.status = "done";
    // 全部完成?
    if (planSteps.value.every((x) => x.status === "done")) {
      planStatus.value = "done";
    }
  }

  function updatePlanSteps(steps) {
    planSteps.value = steps.map((s) => ({ ...s, status: "pending" }));
  }

  function clearPlan() {
    planStatus.value = "idle";
    planSteps.value = [];
    planTask.value = "";
  }

  function clear() {
    traces.value = [];
    currentProgress.value = "";
    clearPlan();
  }

  function setProgress(msg) {
    currentProgress.value = msg || "";
  }

  return {
    traces,
    planStatus,
    planSteps,
    planTask,
    currentProgress,
    addToolCall,
    completeToolCall,
    appendToolResult,
    setPlan,
    setPlanGenerating,
    startPlanExecution,
    startPlanStep,
    donePlanStep,
    updatePlanSteps,
    clearPlan,
    setProgress,
    clear,
  };
});
