package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 在工作区执行 shell 命令。执行 mvn/javac 前会把原项目 src 同步过来。
 * 工作区路径、项目 ID、原项目根目录通过构造函数注入，线程安全。
 */
@Slf4j
public class ShellTool implements Function<ShellTool.Request, String> {

    // 记录已经同步过源码的 workspace+project，避免重复复制（全局共享即可）
    private static final Set<String> SYNCED_SESSIONS = ConcurrentHashMap.newKeySet();

    // mvn test 首次运行可能需要下载依赖、编译、运行测试，60s 严重不够。
    private static final long SHELL_TIMEOUT_SECONDS = 600L;

    /** 允许的命令前缀白名单（大小写不敏感，匹配第一个 token） */
    private static final java.util.Set<String> ALLOWED_COMMANDS = java.util.Set.of(
            "mvn", "mvnw", "javac", "java", "jar",
            "gradle", "gradlew",
            "git",
            "dir", "ls", "pwd", "cd", "echo", "type", "cat", "tree",
            "node", "npm", "npx", "yarn", "pnpm",
            "python", "python3", "pip"
    );

    /** 危险关键字：一旦出现在命令中任意位置就拒绝。 */
    private static final java.util.List<String> DANGEROUS_PATTERNS = java.util.List.of(
            "rm ", "rm\t", "rm-",
            "del ", "erase ", "format ",
            "shutdown", "reboot", "halt",
            "mkfs", "dd ", ":(){:|:&};:",
            "chmod 777", "chown",
            "reg ", "reg.exe", "net user", "net localgroup",
            "powershell", "start-process",
            "curl ", "wget ", "invoke-webrequest",
            "ssh ", "scp ", "sftp ", "telnet ", "ftp ",
            "sudo ", "su -",
            "taskkill", "kill -9",
            "> /dev/sda", "/dev/null 2>&1 &"
    );

    /**
     * 检查命令是否允许。返回 null 表示通过，非 null 是拒绝原因。
     */
    static String validateCommand(String command) {
        if (command == null || command.isBlank()) return "命令为空";
        String lower = command.toLowerCase().trim();
        // 危险关键字
        for (String bad : DANGEROUS_PATTERNS) {
            if (lower.contains(bad)) {
                return "命令被拦截：包含危险关键字 '" + bad.trim() + "'";
            }
        }
        // 多命令拼接：&& || ; | 依次拆分逐个检查首 token
        String[] segments = command.split("&&|\\|\\||;");
        for (String seg : segments) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            // 取首个 token，去掉路径前缀和 .exe 后缀
            String first = s.split("\\s+")[0];
            // 去输送重定向等附加符号
            int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
            if (slash >= 0) first = first.substring(slash + 1);
            if (first.toLowerCase().endsWith(".exe")) first = first.substring(0, first.length() - 4);
            // 变量赋值 / 纯路径 ，跳过
            if (first.contains("=") || first.isEmpty() || first.equals("&") || first.equals("|")) continue;
            String key = first.toLowerCase();
            if (!ALLOWED_COMMANDS.contains(key)) {
                return "命令被拦截：'" + first + "' 不在白名单中。允许的命令：" + ALLOWED_COMMANDS;
            }
        }
        return null;
    }

    private final String workspaceRoot;
    private final String projectId;
    private final String uploadBasePath;

    public ShellTool(String workspaceRoot, String projectId, String uploadBasePath) {
        this.workspaceRoot = workspaceRoot;
        this.projectId = projectId;
        this.uploadBasePath = uploadBasePath;
    }

    public static class Request {
        private String command;
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }

    /**
     * 同步原项目的 src 目录到工作区（只在第一次执行 mvn 命令前复制）
     */
    private void syncProjectSources(String workspaceRoot, String projectId, String uploadBasePath) {
        String key = workspaceRoot + ":" + projectId;
        if (SYNCED_SESSIONS.contains(key)) {
            log.info("[executeShell] 项目源码已同步过，跳过: {}", key);
            return;
        }
        Path originalSrc = Paths.get(uploadBasePath, projectId, "src");
        if (!Files.exists(originalSrc)) {
            log.warn("[executeShell] 原项目不存在 src 目录: {}", originalSrc);
            SYNCED_SESSIONS.add(key);
            return;
        }
        Path workspaceSrc = Paths.get(workspaceRoot, "src");
        try {
            Files.walk(originalSrc).forEach(source -> {
                try {
                    Path relative = originalSrc.relativize(source);
                    Path dest = workspaceSrc.resolve(relative);
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("复制文件失败: {} -> {}", source, e.getMessage());
                }
            });
            SYNCED_SESSIONS.add(key);
            log.info("已同步项目源码: {}", projectId);
        } catch (IOException e) {
            log.error("同步源码失败", e);
        }
    }

    @Override
    public String apply(Request request) {
        String command = request == null ? null : request.getCommand();
        log.info("[executeShell] 调用 command={}, workspaceRoot={}", command, workspaceRoot);
        if (workspaceRoot == null) {
            log.warn("[executeShell] 未设置工作目录");
            return "错误：未设置工作目录";
        }

        // 安全闸：白名单 + 危险关键字拦截
        String reject = validateCommand(command);
        if (reject != null) {
            log.warn("[executeShell] 拒绝执行：{} ｜原命令: {}", reject, command);
            return "错误：" + reject + "\n如需执行请联系管理员调整白名单。";
        }

        if (command != null && (command.contains("mvn") || command.contains("javac"))) {
            if (projectId != null && uploadBasePath != null) {
                log.info("[executeShell] 检测到 mvn/javac 命令，先同步原项目 src 到工作区");
                syncProjectSources(workspaceRoot, projectId, uploadBasePath);
            }
        }

        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(new File(workspaceRoot));
            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());

            long startMs = System.currentTimeMillis();
            log.info("[executeShell] 开始执行... cwd={}, cmd={}", workspaceRoot, command);
            Process process = pb.start();

            // 同步读取进程输出，边读边打到后端日志（实时可见）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[executeShell][stdout] {}", line);
                    output.append(line).append("\n");
                }
            }

            // 输出流结束后再等待进程退出，上限 600s
            boolean finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[executeShell] 命令超时 ({} 秒)，强制终止: {}", SHELL_TIMEOUT_SECONDS, command);
                process.destroyForcibly();
                return "命令执行超时（" + SHELL_TIMEOUT_SECONDS + "秒）\n已收集输出:\n" + output;
            }
            int exitCode = process.exitValue();
            long costMs = System.currentTimeMillis() - startMs;
            log.info("[executeShell] 执行完成 exitCode={}, 耗时={}ms, 输出长度={}", exitCode, costMs, output.length());
            return "退出码: " + exitCode + "\n输出:\n" + output;
        } catch (Exception e) {
            log.error("[executeShell] 命令执行异常", e);
            return "命令执行异常: " + e.getMessage();
        }
    }
}
