package com.zsc.dto;

/**
 * 计划中的单步（Planner 输出，Executor 逐步执行）。
 *
 * @param step        步骤序号（从 1 开始）
 * @param title       简短标题（10 字以内）
 * @param description 详细描述：做什么、改哪个文件、预期产出
 * @param type        类型提示：SEARCH / READ / EDIT / CREATE / TEST / SHELL / OTHER
 * SEARCH   = 搜索文件/代码
 * READ     = 读取文件内容
 * EDIT     = 修改文件内容
 * CREATE   = 创建新文件/文件夹
 * TEST     = 运行测试（单元测试）
 * SHELL    = 执行命令行（shell/maven）
 * OTHER    = 其他操作
 */
public record PlanStep(
        int step,
        String title,
        String description,
        String type
) {}
