# Skill: 添加 Spring 注解

## 触发条件

用户要求加 @Transactional、@Cacheable、@Async、@Scheduled、@Valid 等 Spring 注解

## 执行步骤

1. **探查项目注解使用习惯**：
   - grepSearch 搜索目标注解（如 `@Transactional`）看项目中是否已使用
   - readFile 读取一个已有注解的类，学习注解的使用方式（类级 / 方法级 / 参数级）
2. **确认依赖**：
   - readFile 读取 `pom.xml`，确认相关 starter 已引入：
     - `@Transactional` → `spring-boot-starter-jdbc` 或 `spring-boot-starter-data-jpa`
     - `@Cacheable` → `spring-boot-starter-cache`
     - `@Async` → 需要 `@EnableAsync`
     - `@Scheduled` → 需要 `@EnableScheduling`
     - `@Valid` → `spring-boot-starter-validation`
3. **读取目标文件**：readFile 读取需要加注解的类/方法完整代码
4. **添加注解**：用 editFile 在方法/类/参数前添加注解
   - 如果需要导入，同时添加 import 语句
   - 如果需要配置（如 `@EnableAsync`），在配置类中添加
5. **添加配置**（如果需要）：
   - `@Cacheable` → 确认 `application.yml` 中有缓存配置
   - `@Async` → 确认有线程池配置
6. **运行测试**：executeShell("mvn test")

## 常用注解速查

| 注解                                            | 位置              | 依赖                           |
| ----------------------------------------------- | ----------------- | ------------------------------ |
| `@Transactional(rollbackFor = Exception.class)` | Service 方法      | spring-boot-starter-jdbc       |
| `@Cacheable(value = "users", key = "#id")`      | Service 方法      | spring-boot-starter-cache      |
| `@Async`                                        | Service 方法      | @EnableAsync + 线程池          |
| `@Scheduled(cron = "0 0 * * * ?")`              | 无参方法          | @EnableScheduling              |
| `@Valid`                                        | Controller 参数前 | spring-boot-starter-validation |
| `@NotBlank` / `@NotNull`                        | Entity 字段       | spring-boot-starter-validation |

## 注意事项

- `@Transactional` 默认只回滚 RuntimeException，建议加 `rollbackFor = Exception.class`
- `@Transactional` 同类内部方法调用不生效（没走代理），需要拆分到不同类
- `@Async` 同类内部调用也不生效，同理
