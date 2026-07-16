package com.zsc.controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.zsc.dto.PlanStep;
import com.zsc.entity.ApiResult;
import com.zsc.indexing.IndexingService;
import com.zsc.service.AgentDiffService;
import com.zsc.service.AgentExecutionService;
import com.zsc.service.PlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentExecutionService agentExecutionService;
    private final PlannerService plannerService;
    private final AgentDiffService agentDiffService;
    private final IndexingService indexingService;

    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> execute(@RequestParam String sessionId,
                                                  @RequestParam String conversationId,
                                                  @RequestParam String projectId,
                                                  @RequestParam String task,
                                                  @RequestParam(required = false) String model) throws GraphRunnerException {
        return agentExecutionService.streamExecuteTask(sessionId, conversationId, projectId, task, model);
    }

    /**
     * 生成任务计划（用户可以审阅/编辑后再确认执行）。
     */
    @PostMapping("/plan")
    public ApiResult<List<PlanStep>> generatePlan(@RequestParam String projectId,
                                                  @RequestParam String task,
                                                  @RequestParam(required = false) String model) {
        try {
            List<PlanStep> steps = plannerService.generatePlan(projectId, task, model);
            return ApiResult.success(steps);
        } catch (Exception e) {
            return ApiResult.error(500, "生成计划失败: " + e.getMessage());
        }
    }

    /**
     * 按确认后的计划逐步执行（SSE 流式，带 plan_step_start / plan_step_done 事件）。
     */
    @PostMapping(value = "/execute-plan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> executePlan(@RequestParam String sessionId,
                                                     @RequestParam String conversationId,
                                                     @RequestParam String projectId,
                                                     @RequestParam String task,
                                                     @RequestParam(required = false) String model,
                                                     @RequestBody List<PlanStep> steps) {
        return plannerService.executePlan(sessionId, conversationId, projectId, steps, task, model);
    }

    /**
     * 查看本会话 Agent 在沙箱内改动过的文件与原项目的 unified diff。
     */
    @GetMapping("/workspace-diff")
    public ApiResult<List<AgentDiffService.DiffEntry>> workspaceDiff(@RequestParam String sessionId,
                                                                    @RequestParam String projectId) {
        try {
            return ApiResult.success(agentDiffService.computeDiff(sessionId, projectId));
        } catch (Exception e) {
            log.error("[workspaceDiff] 计算 diff 异常 sessionId={}, projectId={}", sessionId, projectId, e);
            return ApiResult.error(500, "计算 diff 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 应用沙箱修改到原项目（复制文件 + 对每个改动文件做单文件 reindex，增量，毫秒级）。
     */
    @PostMapping("/apply-diff")
    public ApiResult<Integer> applyDiff(@RequestParam String sessionId,
                                        @RequestParam String projectId) {
        try {
            int n = agentDiffService.applyDiff(sessionId, projectId);
            return ApiResult.success(n);
        } catch (Exception e) {
            log.error("[applyDiff] 应用 diff 异常 sessionId={}, projectId={}", sessionId, projectId, e);
            return ApiResult.error(500, "应用 diff 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 一次性全项目重建向量索引（以 uploaded_projects/{projectId}/ 为准）。
     * 用于清理历史脏数据（同名文件不同路径变体、早期双写策略残留等）。
     * 较慢，项目文件多时谨慎使用。
     */
    @PostMapping("/reindex-project")
    public ApiResult<String> reindexProject(@RequestParam String projectId) {
        try {
            indexingService.reindexProjectFromDisk(projectId);
            return ApiResult.success("重建完成");
        } catch (Exception e) {
            log.error("[reindexProject] 全量重建异常 projectId={}", projectId, e);
            return ApiResult.error(500, "全量重建失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 丢弃本会话 diff 记录（沙箱文件保留）。
     */
    @PostMapping("/discard-diff")
    public ApiResult<Void> discardDiff(@RequestParam String sessionId) {
        try {
            agentDiffService.discardDiff(sessionId);
            return ApiResult.success();
        } catch (Exception e) {
            return ApiResult.error(500, "丢弃 diff 失败: " + e.getMessage());
        }
    }
}