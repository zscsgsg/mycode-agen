# Skill: 新增 REST API 接口

## 触发条件

用户要求新增 Controller 接口、REST 端点、HTTP API、新增一个接口、新增一个 URL

## 执行步骤

1. **探查现有 Controller**：
   - grepSearch 搜索 `@RestController` 或 `@Controller` 找到所有 Controller 类
   - readFile 读取一个现有 Controller，学习项目的接口风格：
     - 返回格式（ApiResult 包装 / ResponseEntity 直出 / 直接返回对象）
     - 路径命名（kebab-case `/user-info` / camelCase `/userInfo`）
     - 注解使用（@RequestMapping 前缀 / @GetMapping 具体路径）
     - 参数接收方式（@RequestParam / @RequestBody / @PathVariable）
2. **定位 Service 层**：用 searchCode 找到对应的 Service 类，readFile 读取确认方法签名
3. **定位 Entity/DTO**：grepSearch 搜索相关 Entity 类，确认字段定义
4. **新增接口方法**：用 editFile 在 Controller 类中添加方法
   - 遵循项目已有的注解风格、返回格式、路径命名
   - 如果项目用了 Swagger 注解（@Operation / @Tag），也要加
5. **新增 Service 方法**（如果需要）：在 Service 类中添加业务逻辑
6. **运行测试**：executeShell("mvn test") 确认不破坏现有功能
7. **可选**：如果有测试目录，为新接口写一个集成测试

## 注意事项

- 路径命名必须跟项目现有风格一致（不要一个 `/user-info` 一个 `/getProduct`）
- 如果项目有统一返回体（如 `ApiResult<T>`），新接口也必须用
- 入参校验：如果项目用了 `@Valid` + `@NotBlank`，新接口也要加
- 错误码：如果项目有错误码枚举，新增时复用已有错误码
