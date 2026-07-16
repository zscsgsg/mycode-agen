package com.zsc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *  工作区管理
 */
@Service
public class WorkspaceManager {

    @Value("${codemate.workspace.base-path: D:/codemate_data/workspace}")
    private String basePath;

    /**
     * 这个是为每一个会话创建一个独立的文件夹，用来存放代码、文件、项目结构。
     * @param sessionId
     * @return
     * @throws IOException
     */
    public Path createWorkspace(String sessionId) throws IOException {
        Path dir = Paths.get(basePath, "session_" + sessionId);
        //判断文件是否存在
        if (!Files.exists(dir)) {
            //不存在创建
            Files.createDirectories(dir);
        }
        //存在返回
        return dir;
    }
    //这个直接返回工作区的路径
    public Path getWorkspace(String sessionId) {
        return Paths.get(basePath, "session_" + sessionId);
    }

    /**
     * 初始化 Java 项目环境：
     * - 如果原项目存在 pom.xml，则复制到工作区；否则生成默认 pom.xml
     * - 创建 src/main/java 和 src/test/java 目录（空）
     */
    public void initJavaProject(Path workspace, String projectId, String uploadBasePath) throws IOException {
        // 1. 处理 pom.xml 获取pom.xml的路径
        Path workspacePom = workspace.resolve("pom.xml");
        // 如果 pom.xml 不存在，则尝试从原项目复制
        if (!Files.exists(workspacePom)) {
            // 尝试从原项目复制 pom.xml
            Path originalPom = Paths.get(uploadBasePath, projectId, "pom.xml");
            // 如果原项目 pom.xml 存在，则复制
            if (Files.exists(originalPom)) {
                Files.copy(originalPom, workspacePom);
            } else {
                // 原项目没有 pom.xml，生成一个默认的
                String pomContent = generatePomXml();
                //这个把pomContent写入工作区
                Files.writeString(workspacePom, pomContent);
            }
        }

        // 2. 创建源码目录（空目录，具体文件由 ReadFileTool 按需复制）
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.createDirectories(workspace.resolve("src/test/java"));

        // 3. 可选：如果原项目有 resources 目录，也创建空目录
        Path originalResources = Paths.get(uploadBasePath, projectId, "src/main/resources");
        if (Files.exists(originalResources)) {
            Files.createDirectories(workspace.resolve("src/main/resources"));
        }
    }

    private String generatePomXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 \n" +
                "         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>com.codemate</groupId>\n" +
                "    <artifactId>temp-project</artifactId>\n" +
                "    <version>1.0-SNAPSHOT</version>\n" +
                "    <properties>\n" +
                "        <maven.compiler.source>17</maven.compiler.source>\n" +
                "        <maven.compiler.target>17</maven.compiler.target>\n" +
                "    </properties>\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.junit.jupiter</groupId>\n" +
                "            <artifactId>junit-jupiter</artifactId>\n" +
                "            <version>5.9.2</version>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.apache.maven.plugins</groupId>\n" +
                "                <artifactId>maven-surefire-plugin</artifactId>\n" +
                "                <version>3.0.0-M9</version>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "</project>";
    }
}