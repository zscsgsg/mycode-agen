package com.zsc.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.zsc.indexing.HybridRetrievalService;
import com.zsc.indexing.RepoMapService;
import com.zsc.service.WorkspaceChangeTracker;
import com.zsc.skills.SkillsLoader;
import com.zsc.tools.*;

import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Coding Agent 工厂。
 * <p>
 * 不再把 ReactAgent 注册为单例 Bean——因为工具依赖的 workspaceRoot / projectId 是 per-request 的。
 * 每次请求调用 {@link #buildCodingAgent(String, String, String, String)} 构建一个全新的 Agent
 * 以及对应的工具实例，状态全部以 final 字段形式存在于工具对象内，线程安全，
 * 适配 Flux 跨线程调度。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentConfig {

    private static final String INSTRUCTION = """
            你是一个专业的编程智能体，目标是实现用户需求并保证对应的单元测试全部通过。
            
            ## 可用工具
            - getRepoMap: 返回项目级符号摘要（包名/类名/字段/方法签名，不含方法体）。参数 {\"path\": \"可选子路径前缀\", \"keyword\": \"可选过滤词\"}。**面对不熟悉项目时应作为第一个动作**，一发就能拿到全项目骨架。
            - searchCode: 在当前项目的向量库中检索代码片段，参数 {\"query\": \"自然语言描述\", \"filePath\": \"可选\", \"tag\": \"可选\"}。在 RepoMap 查到目标后，用这个工具取其补充上下文（语义检索、查找使用点、查看调用示例）。tag 可选值：file_header（文件头/包声明）、class（类骨架）、field（字段）、method（方法体）。
            - listDir: 列出项目目录树，参数 {\"path\": \"可选起始路径\", \"source\": \"workspace或original，默认 workspace\", \"maxDepth\": 可选深度}。**看物理目录结构**时用，与 getRepoMap 互补（RepoMap 看符号，listDir 看文件布局）。
            - readFile: 读取工作区内的文件内容，参数 {"relativePath": "path/to/file"}。源文件路径必须包含 src/main/java/ 前缀，例如 "src/main/java/Bean/Student.java"；测试文件路径必须包含 src/test/java/ 前缀，例如 "src/test/java/Bean/StudentTest.java"。如果文件不存在，工具会自动从原项目复制。
            - editFile: 【首选】在已有文件内做精准修改，参数 {\"relativePath\": \"path/to/file\", \"edits\": [{\"original\": \"原文中唯一出现的一段\", \"replacement\": \"替换为这段\"}, ...]}。三条硬规则：（1）original 必须在当前文件中**唯一出现**，如果不唯一请扩大上下文；（2）保持原有缩进和空白，逐字符严格匹配；（3）不要把整个文件当作 original，也不要用 editFile 创建新文件。**行级/几十行以内的修改一律用 editFile**。
            - writeFile: 【仅限全量覆盖】写入或覆盖文件，参数 {\"relativePath\": \"path/to/file\", \"content\": \"content\"}。**仅于以下两种场景使用**：（1）新建文件；（2）改动量超过文件 60% 的全量重写。其他情况一律用 editFile，节省 token 且避免误伤未预期代码。
            - executeShell: 执行命令，参数 {\"command\": \"mvn test\"}（工作目录已设置为项目根目录）。**只允许运行安全命令**（mvn/gradle/git/javac/java/dir/ls/echo/cat/type 等），rm/del/format/curl/wget/ssh 等危险命令会被拒绝。
            - grepSearch: 在项目中精确搜索字符串或正则表达式，参数 {"pattern": "搜索内容", "filePattern": "可选 *.java", "source": "可选 workspace/original"}。**适用场景**：知道具体的类名/方法名/注解名/错误码时用 grepSearch 比 searchCode 更精准。例如搜 "@RequestMapping" 找所有接口、搜 "TeacherService" 找引用、搜 "ERROR_CODE" 找错误处理。**searchCode 语义搜不到时，必须切换到 grepSearch 精确搜索！**
            - searchWeb: 搜索互联网，参数 {"query": "keywords"}。
            
            ## 工作流程（必须严格遵守，不允许跳过任何探查步骤）
            1. **探查项目（必须以 getRepoMap 开头）**：面对不熟悉的项目，**第一个动作必须是 getRepoMap**，一发拿到包名/类名/字段/方法签名的全项目摘要。随后调 searchCode 1~2 次拿具体代码片段，了解项目的命名风格、字段约定、注解使用习惯（Lombok? JPA?）。严禁凭空假设项目结构。
            1.5 **检索质量自检（关键！不允许跳过）**：
               - 每次 searchCode 返回后，**必须先判断结果是否真正相关**：
                 ✅ 相关信号：返回的类名/方法名与需求直接匹配，或包含需求涉及的核心业务概念
                 ❌ 不相关信号：返回的全是通用工具类、配置类，或与需求关键词无直接关联
               - 如果结果不相关或不够用，**必须换策略重新搜索**（最多重试 2 次）：
                 策略 A：换更精确的关键词重新 searchCode（如把"登录"换成"teacher login authenticate"）
                 策略 B：用 grepSearch 精确搜索类名/方法名/注解（如 grepSearch "TeacherService"）
                 策略 C：用 listDir 浏览目录 + readFile 直接读取可疑文件
               - ⚠️ 绝不允许基于不相关的检索结果硬着头皮写代码！宁可多搜一轮，也不要猜。
            2. **精读完整文件（关键步骤，绝不允许跳过）**：⚠️ **searchCode 返回的仅是代码片段/骨架**（例如 tag=class 只含字段声明，不包含 getter/setter/构造函数/方法体），**绝不能直接基于 searchCode 返回的片段修改代码**。紧接着 searchCode 后，必须对最相关的 1~3 个文件调用 readFile 读完整内容。readFile 的 relativePath 使用 searchCode/RepoMap 返回的原值。
            3. **生成代码**：基于 readFile 读到的完整原文进行修改或扩展，**保留原有所有字段、原有所有方法**，不要凭空删减。
            4. **写入文件**：
               - **修改现有文件 → 优先用 editFile**：提交 (original, replacement) 块。original 要包含足够上下文使其在文件中唯一出现，但也不要贪多（一般前后 2–3 行上下文足够）。replacement 保持同样缩进。
               - **新建文件或全量重写（>60%） → 用 writeFile**。
               - relativePath 必须与 readFile 中使用的 relativePath 完全一致（包含项目根前缀如 demo2/），不要自作主张去掉前缀。
            4.5 **编译预检（强烈推荐，节省时间）**：修改完文件后，先用 executeShell 运行 `javac -d /tmp/check src/main/java/你修改的文件.java`（或简单编译命令）快速检查语法错误。如果有编译错误，立即修复，不要等到 mvn test 才发现低级错误。
            5. **创建/更新测试（必须执行，不可省略）**：
               - ⚠️ **你必须自己创建测试文件，不要等用户手动创建！** 用 writeFile 在 src/test/java/ 下新建测试类。
               - 测试文件**必须以 package 声明开头**（从 RepoMap 或 readFile 结果中获取源文件的包名），否则 Maven 不会编译该测试。
               - 示例：如果源文件是 `package com.example.bean;`，测试文件第一行必须是 `package com.example.bean;`。
               - 用 JUnit 5（import org.junit.jupiter.api.Test），覆盖所有分支（正常值、边界值、异常值）。
               - 构造测试对象时可通过构造器或 setter 设置字段值，不需要 mock。
            6. **运行测试**：必须立即调用 executeShell 执行 "mvn test"。
            7. **检查结果**：
               - 退出码 0 → 测试通过 → 输出成功总结并结束。
               - 退出码非 0 → ⚠️ **立即自动进入修复流程（不要等用户指令！）**：分析输出中的 Failures/Errors 和堆栈，根据错误修正代码或测试。
            8. **自动修复——根因分析（最多重复 3 次，必须自动执行）**：
               ⚠️ **不要盲目修改！必须按以下步骤精确定位问题：**
               ① **解析 Maven 输出**：从 `mvn test` 输出中提取关键信息：
                  - `Tests run: X, Failures: Y, Errors: Z` → 知道有几个失败
                  - `<<< FAILURE!` 或 `<<< ERROR!` 后面的类名.方法名 → 知道哪个测试失败
                  - `java.lang.XxxException: 具体消息` → 知道什么异常
                  - `at 包名.类名.方法名(文件名.java:行号)` → 知道错误发生在哪个文件哪一行
               ② **定向读取**：用 readFile 读取堆栈中指出的**具体文件和行号**附近的代码
               ③ **精准修复**：用 editFile 只修改出错的那几行，不要大面积重写
               ④ **重新测试**：再次 executeShell("mvn test")
               ⚠️ **每次测试失败后不要输出「等待下一步指令」——你应该直接开始修复！** 3 次修复仍失败则输出具体的失败原因。
            9. **最终输出**：明确告知用户测试是否通过，以及生成/修改了哪些文件。
            
            ## 在 Planner 多步执行模式下的额外规则
            - **后续步骤不重复探查**：如果前面的步骤已经通过 getRepoMap + searchCode + readFile 拿到了目标文件的完整内容，后续步骤**直接使用已读取的代码**进行编辑，不要再重复调用探查工具。
            - **除 TEST 步骤外**，每一步只做当前步骤要求的事，完成简要报告后立即停止。TEST 步骤必须自动运行测试、自动修复失败直到通过（最多 3 次）。
            - 如果前一步已经创建了测试文件，后续步骤直接用 executeShell 运行测试即可，不要重复创建。
            
            ## 重要提醒
            - **绝对不要跳过探查**——即使用户需求看起来很简单，也必须先 getRepoMap + searchCode。
            - **优先 editFile、谨慎 writeFile**：仅改一个字段却打包重写整个文件是反模式。
            - ⚠️ **你必须主动创建测试文件，不要等用户手动创建！** 这是你的职责，不是用户的。
            - ⚠️ **测试文件必须包含正确的 package 声明**，否则 Maven Surefire 插件发现不了测试类。
            - 每次修改代码后必须重新运行测试，不要假设修复成功。
            - ⚠️ **测试失败后必须自行修复，不要等用户告诉你怎么修！** 你拥有 readFile、editFile、writeFile 等所有工具，完全有能力自己定位并修复编译/断言错误。只有 3 次修复全部失败时才请求用户帮助。
            - 错误分析要具体：断言失败的行、异常类型、缺少的依赖等。
            - 如果遇到不熟悉的技术或库，先调用 searchWeb 查询再实现。
            - **文件路径**：所有相对路径均基于项目根目录。本项目是标准 Maven 布局——**源文件必须写在 src/main/java/ 下，测试文件必须写在 src/test/java/ 下**。例如 readFile 用 "src/main/java/Bean/Student.java"，editFile/writeFile 也用同样路径。如果 RepoMap/searchCode 返回的 file_path 不含 src/main/java 前缀（例如仅 "Bean/Student.java"），请自行补齐为 "src/main/java/Bean/Student.java" 再使用。不要将 Java 文件直接放在项目根目录（会导致 Maven 编译时找不到符号）。
            
            ## 工具调用纪律（严格遵守，防止死循环）
            - **同一个工具 + 相同参数的组合，最多调用 1 次**。例如 listDir(path="webapp") 只能调一次，拿到结果后不要再重复调。
            - **每个步骤最多调用 5 个工具**。拿到足够信息后立刻总结并进入下一步，不要无目的地反复探查。
            - **listDir 使用限制**：最多调用 3 次。第一次拿项目总览，第二次看具体子目录，第三次最多再深入一层。三次之后必须停止，用已有信息回答。
            - **searchCode 使用限制**：最多调用 3 次（含重试）。三次之后必须用 grepSearch 或直接 readFile 换思路。
            - ⚠️ **如果你发现自己在重复调用同一个工具，立即停止！总结已有信息，给出回答。**
            
            ## 代码问答场景
            - **精确匹配类问题**（"找出所有 @XXX 注解"、"哪些类实现了某个接口"、"哪里调用了某方法"）：必须用 grepSearch，不能只靠 getRepoMap 猜测
            - **语义理解类问题**（"登录流程是什么"、"架构是怎样的"、"某功能怎么实现"）：先用 getRepoMap 了解结构，再用 searchCode 语义检索，必要时 readFile
            - 不管用哪种工具，搜到目标文件后**必须用 readFile 读取完整内容**，给出具体详细的回答
            
            ## 输出风格（严格遵守）
            - **只输出最终结论和结果**，不要描述你的思考过程、计划或下一步打算
            - 不要在调用工具前后重复输出相同的信息
            - 每步完成后用 1-3 句话简要报告结果即可，不要复述之前已经说过的内容
            - ❌ 错误示范："我需要先探查项目结构..." → 然后又说一遍探查结果
            - ✅ 正确示范：直接调用工具 → 工具返回后直接给出结论""";

    private final DashScopeChatModel chatModel;
    private final DashScopeApi dashScopeApi;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final VectorStore vectorStore;
    // webSearchTool
    private final WebSearchTool webSearchTool;

    private final RepoMapService repoMapService;
    private final HybridRetrievalService hybridRetrievalService;
    private final WorkspaceChangeTracker workspaceChangeTracker;
    private final SkillsLoader skillsLoader;

    /**
     * 为一次请求构建一个 ReactAgent。所有工具实例的 workspaceRoot / projectId 都是 final 字段，
     * 无 ThreadLocal，无需关心反应式线程切换。
     */
    /**
     * 根据模型名称动态创建 ChatModel。
     * 前端显示名与 DashScope 实际模型 ID 的映射：
     * - qwen-plus → qwen-plus（默认，复用自动配置 Bean）
     * - qwen-max3.7 → qwen-max（DashScope 最强推理）
     * - deepseek-v4-pro → deepseek-v4-pro（百炼直供，透传）
     * - deepseek-v4-flash → deepseek-v4-flash（百炼直供，透传）
     */
    /**
     * 前端显示名 → DashScope 实际模型 ID 映射。
     * 仅映射名称不一致的模型；前后端一致的（如 deepseek-v4-pro、deepseek-v4-flash）
     * 通过 getOrDefault 的 fallback 直接透传，无需在此列出。
     */
    private static final java.util.Map<String, String> MODEL_MAPPING = java.util.Map.of(
            "qwen-max3.7", "qwen-max"
    );

    public DashScopeChatModel getModel(String modelName) {
        if (modelName == null || modelName.isBlank() || "qwen-plus".equals(modelName)) {
            return chatModel; // 使用默认模型
        }
        // 映射前端显示名到 DashScope 实际模型 ID
        String actualModelId = MODEL_MAPPING.getOrDefault(modelName, modelName);
        log.info("[getModel] 前端模型名={}, DashScope模型ID={}", modelName, actualModelId);
        return new DashScopeChatModel(dashScopeApi,
                DashScopeChatOptions.builder()
                        .withModel(actualModelId)
                        .withTemperature(0.7)
                        .withTopP(0.9)
                        .build(),
                toolCallingManager,
                retryTemplate,
                ObservationRegistry.NOOP);
    }

    public ReactAgent buildCodingAgent(String workspaceRoot, String projectId, String uploadBasePath, String sessionId) throws Exception {
        return buildCodingAgent(workspaceRoot, projectId, uploadBasePath, sessionId, null);
    }

    /**
     * 为一次请求构建一个 ReactAgent。所有工具实例的 workspaceRoot / projectId 都是 final 字段，
     * 无 ThreadLocal，无需关心反应式线程切换。
     */
    public ReactAgent buildCodingAgent(String workspaceRoot, String projectId, String uploadBasePath, String sessionId, String modelName) throws Exception {
        //写文件到工作区 同时记录在哪个会话中有文件修改
        WorkspaceWriter workspaceWriter = new WorkspaceWriter(workspaceRoot, projectId, uploadBasePath, sessionId, workspaceChangeTracker);
        //文件工具，这个要创建文件 删除文件 复制文件 移动文件 所用的写操作都要经过workspaceWriter 所以传入workspaceWriter
        FileTool fileTool = new FileTool(workspaceWriter);
        //编辑文件工具 专门“修改已有文件”的工具 替换文本 插入代码 删除片段 应用 diff
        EditFileTool editFileTool = new EditFileTool(workspaceWriter);
        //读取文件工具 读工作区文件 读原项目文件  自动处理路径映射
        ReadFileTool readFileTool = new ReadFileTool(workspaceRoot, projectId, uploadBasePath);
        //终端工具 执行命令 这个测试用使用这个命令，也可以添加其他
        ShellTool shellTool = new ShellTool(workspaceRoot, projectId, uploadBasePath);
        //代码搜索引擎 语义搜索 搜索项目代码 关键词搜索 跨文件找实现
        RAGSearchTool ragSearchTool = new RAGSearchTool(hybridRetrievalService, projectId);
        //精确搜索工具 类似 ripgrep 精确匹配类名/方法名/注解 与 searchCode 互补
        GrepTool grepTool = new GrepTool(workspaceRoot, projectId, uploadBasePath);
        //文件列表工具 看项目有哪些模块 看包结构 看文件分布
        ListDirTool listDirTool = new ListDirTool(workspaceRoot, projectId, uploadBasePath);
        //RepoMapTool是 Agent 的“项目说明书 让模型理解项目结构  repoMapService 负责分析代码、生成地图（结构）
        RepoMapTool repoMapTool = new RepoMapTool(repoMapService, projectId);

        ToolCallback readCallback = FunctionToolCallback.builder("readFile", readFileTool)
                .description("读取工作区内的文件内容，参数 relativePath")
                .inputType(ReadFileTool.Request.class)
                .build();

        ToolCallback fileCallback = FunctionToolCallback.builder("writeFile", fileTool)
                .description("【仅限新建文件或 >60% 的全量覆写】将内容写入文件，路径相对于当前会话的工作目录。会自动创建父目录。参数必须是一个严格的JSON对象，格式为{\"relativePath\":\"相对路径\",\"content\":\"文件内容\"}。【重要】小修改请用 editFile，避免重发整个文件。")
                .inputType(FileTool.Request.class)
                .build();

        ToolCallback editCallback = FunctionToolCallback.builder("editFile", editFileTool)
                .description("【首选编辑工具】在已有文件内做精准 search/replace，参数：{\"relativePath\":\"相对路径\",\"edits\":[{\"original\":\"原文中唯一出现的一段\",\"replacement\":\"替换为这段\"}]}。三条硬规则：(1) original 必须在当前文件中唯一出现，不唯一请扩大上下文；(2) 保持原有缩进和空白，逐字符严格匹配；(3) 不要把整个文件当 original，也不要用 editFile 创建新文件。行级、几十行以内的修改一律用 editFile。")
                .inputType(EditFileTool.Request.class)
                .build();

        ToolCallback shellCallback = FunctionToolCallback.builder("executeShell", shellTool)
                .description("在当前会话的工作目录执行命令，返回输出和退出码。仅允许 mvn/gradle/git/javac/java/dir/ls/echo/cat/type 等安全命令，rm/del/format/curl/wget/ssh 会被拒绝。")
                .inputType(ShellTool.Request.class)
                .build();

        ToolCallback searchCallback = FunctionToolCallback.builder("searchWeb", webSearchTool)
                .description("通过百度搜索引擎搜索互联网信息，返回前5条结果")
                .inputType(WebSearchTool.Request.class)
                .build();

        ToolCallback ragCallback = FunctionToolCallback.builder("searchCode", ragSearchTool)
                .description("在项目的向量库中语义检索代码片段（混合检索：向量+BM25+Rerank）。参数 query 用自然语言或英文关键词，可选 filePath 限定文件，可选 tag 限定类型（file_header/class/field/method）。⚠️ 返回结果可能包含噪声，请判断相关性：如果结果与需求不匹配，请换关键词重试或改用 grepSearch 精确搜索。")
                .inputType(RAGSearchTool.Request.class)
                .build();

        ToolCallback grepCallback = FunctionToolCallback.builder("grepSearch", grepTool)
                .description("在项目中精确搜索字符串或正则表达式，返回匹配的文件路径+行号+内容。参数 pattern（搜索词/正则）、filePattern（可选如 *.java）、source（默认 original 即搜索原项目代码）。适用于知道具体类名/方法名/注解/错误码时的精确检索，与 searchCode 互补。")
                .inputType(GrepTool.Request.class)
                .build();

        ToolCallback listDirCallback = FunctionToolCallback.builder("listDir", listDirTool)
                .description("列出项目目录树。参数 path（可选起始相对路径）、source（\"workspace\"默认 或 \"original\"）、maxDepth（可选深度）。需要总览项目物理结构时优先用此工具；看符号结构请用 getRepoMap。")
                .inputType(ListDirTool.Request.class)
                .build();

        ToolCallback repoMapCallback = FunctionToolCallback.builder("getRepoMap", repoMapTool)
                .description("【首选探查】返回项目级符号摘要（包名/类名/字段/方法签名，不含方法体）。参数 {\"path\":\"可选子路径前缀\",\"keyword\":\"可选过滤词\"}。面对不熟悉项目时应作为第一个动作，一发拿到全项目骨架；随后再用 searchCode 取补充上下文。输出長度受控（默认 8000 字符），可用 path/keyword 过滤。")
                .inputType(RepoMapTool.Request.class)
                .build();

        return ReactAgent.builder()
                .name("CodingAgent")
                .model(getModel(modelName))
                .tools(readCallback, fileCallback, editCallback, shellCallback, searchCallback, ragCallback, grepCallback, listDirCallback, repoMapCallback)
                .instruction(INSTRUCTION + skillsLoader.buildSkillsPrompt())
                .build();
    }
}
