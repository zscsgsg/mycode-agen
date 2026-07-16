package com.zsc.skills;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Skills 动态加载器。
 * <p>
 * 从指定目录加载所有 .md 文件作为 Agent 的专业技能（Skills），
 * 每个 Skill 文件包含触发条件和执行步骤，在构建 Agent 时注入 System Prompt。
 * <p>
 * 架构三层：Tools（工具能力）→ Instructions（通用规则）→ Skills（领域 SOP）。
 * Skills 是最高层的领域知识，让 Agent 知道"怎么写单元测试"、"怎么加 REST 接口"等高频任务的标准化流程。
 */
@Component
@Slf4j
public class SkillsLoader {

    @Value("${codemate.skills.path:./skills}")
    private String skillsPath;

    /** 加载后的 Skill 内容（文件名 → 文件内容） */
    private final Map<String, String> skills = new LinkedHashMap<>();

    @PostConstruct
    public void loadSkills() {
        Path dir = Paths.get(skillsPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            log.warn("[SkillsLoader] Skills 目录不存在: {}, Agent 将以无 Skill 模式运行", skillsPath);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                try {
                    String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                    String name = file.getFileName().toString().replace(".md", "");
                    skills.put(name, content);
                    log.info("[SkillsLoader] 加载 Skill: {} ({} 字符)", name, content.length());
                } catch (IOException e) {
                    log.warn("[SkillsLoader] 读取 Skill 文件失败: {}, 错误: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[SkillsLoader] 扫描 Skills 目录失败", e);
        }

        log.info("[SkillsLoader] 共加载 {} 个 Skills", skills.size());
    }

    /**
     * 获取所有已加载的 Skill 名称列表
     */
    public Set<String> getSkillNames() {
        return Collections.unmodifiableSet(skills.keySet());
    }

    /**
     * 将所有 Skills 拼接为 System Prompt 的附加段。
     * 如果没有加载任何 Skill，返回空字符串。
     */
    public String buildSkillsPrompt() {
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 专业技能（Skills）\n");
        sb.append("你拥有以下专业技能。当用户需求匹配某个 Skill 的触发条件时，**优先按照该 Skill 的步骤执行**，比通用流程更精准高效。\n");
        sb.append("如果没有匹配的 Skill，按默认工作流程执行。\n\n");

        for (Map.Entry<String, String> entry : skills.entrySet()) {
            sb.append("---\n");
            sb.append(entry.getValue()).append("\n");
        }

        return sb.toString();
    }
}
