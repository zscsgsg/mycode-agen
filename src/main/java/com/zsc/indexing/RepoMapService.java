package com.zsc.indexing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.javadoc.Javadoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目级符号摘要（RepoMap）。
 * <p>
 * 扫描原项目下所有 .java 文件，抽取包名、类签名、字段名+类型、方法签名（不含方法体）。
 * 给 AI 一张「俯视图」，避免每次都靠 searchCode 抽样摸黑。
 * <p>
 * 缓存按 projectId 维度持有；上传完成后由 {@link IndexingService} 异步预热；
 * 第一次工具调用兜底同步构建。
 */
@Slf4j
@Service
public class RepoMapService {

    /** 单次 query 最大输出字符数，超出则降级为「文件 + 类名 + 方法名」紧凑摘要。 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /** 单文件最多列出的字段/方法数（防止 200 字段的实体撑爆输出）。 */
    private static final int MAX_FIELDS_PER_CLASS = 30;
    private static final int MAX_METHODS_PER_CLASS = 50;

    @Value("${codemate.upload.base-path:D:/codemate_data/uploaded_projects}")
    private String uploadBasePath;

    /** projectId -> (相对路径 -> 文件符号摘要) */
    private final Map<String, Map<String, FileSymbols>> cache = new ConcurrentHashMap<>();

    /**
     * 异步构建：通常由 IndexingService 在索引完成后调用。失败时不抛。
     */
    public void buildAsync(String projectId) {
        // [JDK 21 虚拟线程] 异步构建 RepoMap，不占用平台线程
        Thread.ofVirtual()
                .name("repomap-build-" + projectId)
                .start(() -> {
                    try { build(projectId); } catch (Exception e) {
                        log.warn("[repoMap] 构建失败 projectId={}", projectId, e);
                    }
                });
    }

    /** 同步构建（兜底）：扫描整个项目目录。 */
    public synchronized Map<String, FileSymbols> build(String projectId) {
        Path root = Paths.get(uploadBasePath, projectId);
        if (!Files.isDirectory(root)) {
            log.warn("[repoMap] 项目目录不存在: {}", root);
            cache.put(projectId, Map.of());
            return Map.of();
        }
        long start = System.currentTimeMillis();
        Map<String, FileSymbols> map = new LinkedHashMap<>();
        // 扫描 walk遍历目录
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)// 只看文件
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        // 跳过备份/// 跳过 target、build、.git 等目录
                        return !s.contains("/modifications/")
                                && !s.contains("/target/")
                                && !s.contains("/build/")
                                && !s.contains("/.git/");
                    })
                    .sorted()
                    .toList();
            for (Path p : javaFiles) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                FileSymbols syms = parseFile(p);
                if (syms != null && !syms.classes.isEmpty()) {
                    map.put(rel, syms);
                }
            }
        } catch (IOException e) {
            log.error("[repoMap] 扫描失败 projectId={}", projectId, e);
        }
        cache.put(projectId, map);
        log.info("[repoMap] 构建完成 projectId={} files={} 耗时 {}ms",
                projectId, map.size(), System.currentTimeMillis() - start);
        return map;
    }

    /**
     * 查询 RepoMap，返回 markdown 摘要。
     * @param pathPrefix 可选，仅返回相对路径以此为前缀的文件
     * @param keyword    可选，类名/方法名/字段名/包名包含此关键字（大小写不敏感）
     */
    public String query(String projectId, String pathPrefix, String keyword) {
        Map<String, FileSymbols> map = cache.get(projectId);
        if (map == null) {
            map = build(projectId);
        }
        if (map.isEmpty()) {
            return "（项目 " + projectId + " 暂无符号信息。请确认项目已上传并被索引。）";
        }

        String prefix = pathPrefix == null ? "" : pathPrefix.trim().replace('\\', '/');
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();

        List<Map.Entry<String, FileSymbols>> matched = map.entrySet().stream()
                .filter(e -> prefix.isEmpty() || e.getKey().startsWith(prefix) || e.getKey().contains("/" + prefix))
                .filter(e -> kw.isEmpty() || matchesKeyword(e.getValue(), kw))
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return "（无匹配文件。projectId=" + projectId
                    + " pathPrefix=" + prefix + " keyword=" + kw + " 总文件数=" + map.size() + "）";
        }

        // 第一遍：详细输出；如超长则降级为紧凑摘要
        String detailed = render(matched, false);
        if (detailed.length() <= MAX_OUTPUT_CHARS) return detailed;
        String compact = render(matched, true);
        if (compact.length() <= MAX_OUTPUT_CHARS) {
            return "（结果过多，已压缩为类/方法名摘要；如需详情请缩小 path 或 keyword 范围。）\n\n" + compact;
        }
        // 仍过长 → 截断
        return "（结果严重超长，仅展示前部；请缩小 path 或 keyword 范围。）\n\n"
                + compact.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
    }

    private boolean matchesKeyword(FileSymbols syms, String kw) {
        if (syms.packageName != null && syms.packageName.toLowerCase().contains(kw)) return true;
        for (ClassSymbols c : syms.classes) {
            if (c.name.toLowerCase().contains(kw)) return true;
            for (String f : c.fields) if (f.toLowerCase().contains(kw)) return true;
            for (String m : c.methods) if (m.toLowerCase().contains(kw)) return true;
        }
        return false;
    }

    private String render(List<Map.Entry<String, FileSymbols>> matched, boolean compact) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, FileSymbols> e : matched) {
            String path = e.getKey();
            FileSymbols syms = e.getValue();
            sb.append("## ").append(path).append("\n");
            if (syms.packageName != null && !syms.packageName.isEmpty()) {
                sb.append("package ").append(syms.packageName).append(";\n");
            }
            for (ClassSymbols c : syms.classes) {
                sb.append(c.signature).append(" {\n");
                if (compact) {
                    if (!c.fields.isEmpty()) {
                        sb.append("  fields: ").append(c.fields.size()).append("\n");
                    }
                    if (!c.methods.isEmpty()) {
                        sb.append("  methods: ").append(c.methods.stream()
                                        .map(this::extractMethodName)
                                        .distinct()
                                        .collect(Collectors.joining(", ")))
                                .append("\n");
                    }
                } else {
                    int fShown = Math.min(c.fields.size(), MAX_FIELDS_PER_CLASS);
                    for (int i = 0; i < fShown; i++) {
                        sb.append("  ").append(c.fields.get(i)).append("\n");
                    }
                    if (c.fields.size() > fShown) {
                        sb.append("  ... +").append(c.fields.size() - fShown).append(" more fields\n");
                    }
                    int mShown = Math.min(c.methods.size(), MAX_METHODS_PER_CLASS);
                    for (int i = 0; i < mShown; i++) {
                        sb.append("  ").append(c.methods.get(i)).append("\n");
                    }
                    if (c.methods.size() > mShown) {
                        sb.append("  ... +").append(c.methods.size() - mShown).append(" more methods\n");
                    }
                }
                sb.append("}\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String extractMethodName(String signature) {
        // signature 形如 "public String getName()"
        int paren = signature.indexOf('(');
        if (paren < 0) return signature;
        String head = signature.substring(0, paren);
        int sp = head.lastIndexOf(' ');
        return sp < 0 ? head : head.substring(sp + 1);
    }

    private FileSymbols parseFile(Path file) {
        try {
            // 解析文件
            String code = Files.readString(file);
            CompilationUnit cu = new JavaParser().parse(code).getResult().orElse(null);
            if (cu == null) return null;
            //装这个文件的所有结构
            FileSymbols fs = new FileSymbols();
            fs.packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration
                        || type instanceof EnumDeclaration
                        || type instanceof AnnotationDeclaration)) continue;

                ClassSymbols cs = new ClassSymbols();
                cs.name = type.getNameAsString();
                cs.signature = buildClassSignature(type);
               // 得到所有字段
                for (FieldDeclaration field : type.getFields()) {

                    cs.fields.add(formatField(field));
                }
                //得到所有方法
                for (MethodDeclaration method : type.getMethods()) {
                    cs.methods.add(formatMethod(method));
                }
                //构造方法
                if (type instanceof ClassOrInterfaceDeclaration cid) {
                    for (ConstructorDeclaration ctor : cid.getConstructors()) {
                        cs.methods.add(formatConstructor(ctor));
                    }
                }
                fs.classes.add(cs);
            }
            return fs;
        } catch (Exception e) {
            log.debug("[repoMap] 解析失败 {}: {}", file, e.getMessage());
            return null;
        }
    }

    private String buildClassSignature(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        // 仅保留较关键的注解
        type.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .forEach(s -> sb.append(s).append(" "));
        String kind;
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            kind = cid.isInterface() ? "interface" : "class";
        } else if (type instanceof EnumDeclaration) {
            kind = "enum";
        } else {
            kind = "@interface";
        }
        sb.append(kind).append(" ").append(type.getNameAsString());
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            if (cid.getExtendedTypes().isNonEmpty()) {
                sb.append(" extends ").append(cid.getExtendedTypes().stream()
                        .map(t -> t.getNameAsString()).collect(Collectors.joining(", ")));
            }
            if (cid.getImplementedTypes().isNonEmpty()) {
                sb.append(" implements ").append(cid.getImplementedTypes().stream()
                        .map(t -> t.getNameAsString()).collect(Collectors.joining(", ")));
            }
        }
        return sb.toString();
    }

    private String formatField(FieldDeclaration field) {
        StringBuilder sb = new StringBuilder();
        field.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .forEach(s -> sb.append(s).append(" "));
        sb.append(modifiers(field.getModifiers()));
        sb.append(field.getElementType().asString()).append(" ");
        sb.append(field.getVariables().stream()
                .map(v -> v.getNameAsString())
                .collect(Collectors.joining(", ")));
        sb.append(";");
        return sb.toString();
    }

    private String formatMethod(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder();
        method.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .forEach(s -> sb.append(s).append(" "));
        sb.append(modifiers(method.getModifiers()));
        sb.append(method.getType().asString()).append(" ");
        sb.append(method.getNameAsString()).append("(");
        sb.append(method.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", ")));
        sb.append(");");
        method.getJavadoc().map(Javadoc::getDescription).ifPresent(d -> {
            String text = d.toText().strip();
            if (!text.isEmpty()) {
                int nl = text.indexOf('\n');
                if (nl > 0) text = text.substring(0, nl);
                if (text.length() > 80) text = text.substring(0, 80) + "...";
                sb.append("  // ").append(text);
            }
        });
        return sb.toString();
    }

    private String formatConstructor(ConstructorDeclaration ctor) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifiers(ctor.getModifiers()));
        sb.append(ctor.getNameAsString()).append("(");
        sb.append(ctor.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", ")));
        sb.append(");");
        return sb.toString();
    }

    private String modifiers(com.github.javaparser.ast.NodeList<Modifier> mods) {
        if (mods == null || mods.isEmpty()) return "";
        return mods.stream()
                .map(m -> m.getKeyword().asString())
                .collect(Collectors.joining(" ")) + " ";
    }

    /** 单文件符号集合。 */
    public static class FileSymbols {
        public String packageName = ""; // 包名
        public List<ClassSymbols> classes = new ArrayList<>(); // 类
    }

    /** 单类/接口/枚举符号集合。 */
    public static class ClassSymbols {
        public String name; // 类的名字
        public String signature; // 类的完整签名（修饰符+继承+实现）
        public List<String> fields = new ArrayList<>(); // 类的成员变量
        public List<String> methods = new ArrayList<>(); // 类的方法
    }
}
