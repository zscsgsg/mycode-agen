package com.zsc.indexing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CodeChunker implements DocumentTransformer {

    private final TextSplitter textSplitter;
    private static final int MAX_LINES_PER_SLICE = 100;   // 每个子切片最大行数
    private static final int MAX_TOKEN_PER_SLICE = 800;   // 每个切片最大token数

    private static final Set<String> IMPORTANT_FIELD_ANNOTATIONS = Set.of(
            // Spring & Spring Boot
            "Autowired",
            "Value",
            "Resource",
            "Inject",
            "Qualifier",
            "Lookup",
            "Lazy",
            "PersistentContext",
            "PersistenceUnit",
            // Jakarta / Java EE
            "jakarta.annotation.Resource",
            "jakarta.inject.Inject",
            "jakarta.persistence.PersistenceContext",
            "jakarta.persistence.PersistenceUnit",
            "jakarta.ejb.EJB",
            // Lombok
            "Getter",
            "Setter",
            "Builder.Default",
            "NonNull",
            // 配置类相关
            "ConfigurationProperties",
            "NacosValue",
            "ApolloValue"
    );

    public CodeChunker(TextSplitter textSplitter) {
        this.textSplitter = textSplitter;
    }
    //代码切片器 产出 4 类切片（file_header, class, field, method），
    // 每个切片前注入 header 注释（含文件路径、行号、包名、类名、方法名），用于 BM25 全文索引和 LLM 引用
    @Override
    public List<Document> apply(List<Document> documents) {
        //用于存放切片
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            // 获取完整内容
            String content = doc.getText();
            // 获取文件名
            String fileName = (String) doc.getMetadata().get("file_name");
            // Java 文件
            if (fileName != null && fileName.endsWith(".java")) {
                result.addAll(parseJava(content, doc.getMetadata()));
            } else if (fileName != null && fileName.endsWith(".py")) {
                result.addAll(parsePython(content, doc.getMetadata()));
            } else {
                // 非结构化文件：在每个切片前补一个来源 header，便于 LLM 与 BM25 命中
                List<Document> plain = textSplitter.apply(List.of(doc));
                for (Document p : plain) {
                    String filePath = (String) p.getMetadata().getOrDefault("file_path", fileName);
                    String header = "// [" + filePath + "] tag=text\n";
                    result.add(new Document(header + p.getText(), p.getMetadata()));
                }
            }
        }
        return result;
    }

    /**
     * 构建源码 header 注释，写到 chunk 文本最前面，让 LLM 能看到归属、行号。
     * 同时被 BM25 全文索引收录，关键词检索能命中类名/方法名/文件名。
     */
    private String buildHeader(String filePath, String tag, String packageName,
                                String className, String methodName,
                                Integer startLine, Integer endLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("// [").append(filePath == null ? "unknown" : filePath);
        if (startLine != null && endLine != null) {
            sb.append(":").append(startLine).append("-").append(endLine);
        }
        sb.append("] tag=").append(tag);
        if (packageName != null && !packageName.isEmpty()) {
            sb.append(" package=").append(packageName);
        }
        if (className != null && !className.isEmpty()) {
            sb.append(" class=").append(className);
        }
        if (methodName != null && !methodName.isEmpty()) {
            sb.append(" method=").append(methodName);
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 解析 Java 代码，生成切片，并为每个切片添加 package_name 元数据
     */
    private List<Document> parseJava(String code, Map<String, Object> parentMeta) {
        //用于存放切片
        List<Document> docs = new ArrayList<>();
        // JavaParser：专门解析 Java 代码的工具 这个返回Java 代码的语法树（AST）
        CompilationUnit cu = new JavaParser().parse(code).getResult().orElse(null);
        if (cu == null) return docs;

        // 获取包名（如果没有 package 声明，则为空字符串）
        String packageName = cu.getPackageDeclaration()
                //把包名转换成字符串
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
        // 获取文件路径
        String filePath = (String) parentMeta.getOrDefault("file_path",
                parentMeta.getOrDefault("file_name", "unknown"));

        // 1. 文件头切片
        String fileHeader = extractHeader(cu);
        if (!fileHeader.isBlank()) {
            Map<String, Object> meta = new HashMap<>(parentMeta);
            meta.put("tag", "file_header");
            meta.put("package_name", packageName);
            //这个变成 [com/zsc/service/AgentDiffService.java] tag=file_header package=com.zsc.service 让 AI 知道代码来源
            String hdr = buildHeader(filePath, "file_header", packageName, null, null, null, null);
            docs.add(new Document(hdr + fileHeader, meta));
        }

        // 2. 处理每个顶层类型
        for (TypeDeclaration<?> type : cu.getTypes()) {
            // 判断是否为类或接口、枚举
            if (type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration) {
                // 2.1 类结构切片（包含包名、类签名、字段声明，不含方法体）
                String classSkeleton = buildClassSkeleton(type, packageName);
                Map<String, Object> classMeta = new HashMap<>(parentMeta);
                classMeta.put("tag", "class");
                classMeta.put("name", type.getNameAsString());
                classMeta.put("language", "java");
                classMeta.put("package_name", packageName);
                Integer cStart = type.getRange().map(r -> r.begin.line).orElse(null);
                Integer cEnd = type.getRange().map(r -> r.end.line).orElse(null);
                if (cStart != null) classMeta.put("start_line", cStart);
                if (cEnd != null) classMeta.put("end_line", cEnd);
                String classHdr = buildHeader(filePath, "class", packageName,
                        type.getNameAsString(), null, cStart, cEnd);
                docs.add(new Document(classHdr + classSkeleton, classMeta));

                // 2.2 重要字段切片
                for (FieldDeclaration field : type.getFields()) {
                    if (isImportantField(field)) {
                        String fieldCode = field.toString();
                        Map<String, Object> fieldMeta = new HashMap<>(parentMeta);
                        fieldMeta.put("tag", "field");
                        fieldMeta.put("field_name", getFieldNames(field));
                        fieldMeta.put("parent_class", type.getNameAsString());
                        fieldMeta.put("language", "java");
                        fieldMeta.put("package_name", packageName);
                        Integer fStart = field.getRange().map(r -> r.begin.line).orElse(null);
                        Integer fEnd = field.getRange().map(r -> r.end.line).orElse(null);
                        if (fStart != null) fieldMeta.put("start_line", fStart);
                        if (fEnd != null) fieldMeta.put("end_line", fEnd);
                        String fieldHdr = buildHeader(filePath, "field", packageName,
                                type.getNameAsString(), getFieldNames(field), fStart, fEnd);
                        docs.add(new Document(fieldHdr + fieldCode, fieldMeta));
                    }
                }

                // 2.3 方法切片
                for (MethodDeclaration method : type.getMethods()) {
                    // 方法体
                    String methodCode = method.toString();
                    // 估计方法体包含的 token 数
                    int estimatedTokens = estimateTokenCount(methodCode);
                    // 如果方法体包含的 token 数超过阈值
                    if (estimatedTokens > MAX_TOKEN_PER_SLICE) {
                        // 超长方法：按行切分（但仍保留包名元数据）
                        docs.addAll(splitMethodByLines(method, parentMeta, type.getNameAsString(), packageName, filePath));
                    } else {
                        Map<String, Object> methodMeta = buildMethodMeta(parentMeta, method, type.getNameAsString(), packageName);
                        Integer mStart = (Integer) methodMeta.get("start_line");
                        Integer mEnd = (Integer) methodMeta.get("end_line");
                        String methodHdr = buildHeader(filePath, "method", packageName,
                                type.getNameAsString(), method.getNameAsString(), mStart, mEnd);
                        docs.add(new Document(methodHdr + methodCode, methodMeta));
                    }
                }
            }
        }
        return docs;
    }

    /**
     * 构建类结构字符串（可选包含 package 声明）
     */
    private String buildClassSkeleton(TypeDeclaration<?> type, String packageName) {
        StringBuilder sb = new StringBuilder();
        // 在文本中显式加入包名，增强检索
        if (packageName != null && !packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        // 注解
        type.getAnnotations().forEach(ann -> sb.append(ann).append("\n"));

        // 推断关键字
        String keyword;
        //判断类型到底是 class /interface/enum / 注解
        if (type instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration classOrInterface = (ClassOrInterfaceDeclaration) type;
            keyword = classOrInterface.isInterface() ? "interface" : "class";
        } else if (type instanceof EnumDeclaration) {
            keyword = "enum";
        } else if (type instanceof AnnotationDeclaration) {
            keyword = "@interface";
        } else {
            keyword = "class";
        }
        sb.append(keyword).append(" ");
        // 类名
        sb.append(type.getNameAsString());

        // 处理继承/实现
        if (type instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) type;
            if (cls.getExtendedTypes().isNonEmpty()) {
                sb.append(" extends ").append(cls.getExtendedTypes().get(0).getNameAsString());
            }
            if (cls.getImplementedTypes().isNonEmpty()) {
                sb.append(" implements ");
                sb.append(cls.getImplementedTypes().stream()
                        .map(t -> t.getNameAsString())
                        .collect(Collectors.joining(", ")));
            }
        } else if (type instanceof EnumDeclaration) {
            EnumDeclaration enumDecl = (EnumDeclaration) type;
            if (enumDecl.getImplementedTypes().isNonEmpty()) {
                sb.append(" implements ");
                sb.append(enumDecl.getImplementedTypes().stream()
                        .map(t -> t.getNameAsString())
                        .collect(Collectors.joining(", ")));
            }
        }
        sb.append(" {\n");

        // 字段（仅保留字段声明）
        for (FieldDeclaration field : type.getFields()) {
            sb.append("    ").append(field.toString().replace("\n", "\n    ")).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 按行切分超长方法（每个切片仍携带包名）
     */
    private List<Document> splitMethodByLines(MethodDeclaration method, Map<String, Object> parentMeta,
                                              String parentClass, String packageName, String filePath) {
        List<Document> slices = new ArrayList<>();
        String methodCode = method.toString();
        String[] lines = methodCode.split("\n");
        int totalLines = lines.length;
        int startLineNumber = method.getRange().map(r -> r.begin.line).orElse(0);

        for (int i = 0; i < totalLines; i += MAX_LINES_PER_SLICE) {
            int end = Math.min(i + MAX_LINES_PER_SLICE, totalLines);
            String chunk = String.join("\n", Arrays.copyOfRange(lines, i, end));
            Map<String, Object> meta = buildMethodMeta(parentMeta, method, parentClass, packageName);
            meta.put("sub_slice", (i / MAX_LINES_PER_SLICE) + 1);
            meta.put("slice_lines", chunk.split("\n").length);
            int absStart = startLineNumber + i;
            int absEnd = startLineNumber + end - 1;
            meta.put("start_line", absStart);
            meta.put("end_line", absEnd);
            String hdr = buildHeader(filePath, "method", packageName,
                    parentClass, method.getNameAsString(), absStart, absEnd);
            slices.add(new Document(hdr + chunk, meta));
        }
        return slices;
    }

    /**
     * 构建方法切片的元数据（包含包名）
     */
    private Map<String, Object> buildMethodMeta(Map<String, Object> parentMeta, MethodDeclaration method,
                                                String parentClass, String packageName) {
        Map<String, Object> meta = new HashMap<>(parentMeta);
        meta.put("tag", "method");
        meta.put("method_name", method.getNameAsString());
        meta.put("parent_class", parentClass);
        meta.put("language", "java");
        meta.put("package_name", packageName);
        method.getRange().ifPresent(r -> {
            meta.put("start_line", r.begin.line);
            meta.put("end_line", r.end.line);
        });
        return meta;
    }

    private String extractHeader(CompilationUnit cu) {
        StringBuilder header = new StringBuilder();
        // 包声明
        cu.getPackageDeclaration().ifPresent(pkg -> header.append(pkg).append("\n"));
        // 导入声明
        for (ImportDeclaration imp : cu.getImports()) {
            header.append(imp).append("\n");
        }
        return header.toString().trim();
    }

    private boolean isImportantField(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(ann -> IMPORTANT_FIELD_ANNOTATIONS.contains(ann.getNameAsString()));
    }

    private String getFieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(var -> var.getNameAsString())
                .collect(Collectors.joining(", "));
    }

    private int estimateTokenCount(String text) {
        return text.length() / 4;
    }

    private List<Document> parsePython(String code, Map<String, Object> parentMeta) {
        // Python 扩展点（同样可添加包名，但需要解析）
        return Collections.emptyList();
    }
}