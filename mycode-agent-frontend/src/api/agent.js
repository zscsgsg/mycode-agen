import { postSSE } from "@/utils/sse";
import request from "@/utils/request";

/**
 * 编程智能体执行（SSE 流式）
 * POST /agent/execute?sessionId=&conversationId=&projectId=&task=
 * sessionId 复用 conversationId
 */
export function agentExecuteStream({
  sessionId,
  conversationId,
  projectId,
  task,
  model,
  signal,
  onText,
  onEvent,
  onDone,
  onError,
}) {
  return postSSE("/api/agent/execute", {
    params: {
      sessionId: sessionId || conversationId,
      conversationId,
      projectId,
      task,
      model,
    },
    signal,
    onText,
    onEvent,
    onDone,
    onError,
  });
}

/**
 * 生成任务计划（同步 POST）
 * POST /agent/plan?projectId=&task=
 */
export function generatePlan(projectId, task, model) {
  return request.post("/agent/plan", null, {
    params: { projectId, task, model },
  });
}

/**
 * 按计划逐步执行（SSE 流式）
 * POST /agent/execute-plan?sessionId=&conversationId=&projectId=&task=
 * Body: PlanStep[]
 */
export function executePlanStream({
  sessionId,
  conversationId,
  projectId,
  task,
  steps,
  model,
  signal,
  onText,
  onEvent,
  onDone,
  onError,
}) {
  return postSSE("/api/agent/execute-plan", {
    params: {
      sessionId: sessionId || conversationId,
      conversationId,
      projectId,
      task,
      model,
    },
    body: steps,
    signal,
    onText,
    onEvent,
    onDone,
    onError,
  });
}
