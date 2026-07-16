# Skill: 添加全局异常处理

## 触发条件

用户要求添加异常处理、@ExceptionHandler、@RestControllerAdvice、统一错误处理、错误码

## 执行步骤

1. **探查现有异常处理**：
   - grepSearch 搜索 `@ExceptionHandler` 或 `@RestControllerAdvice` 看是否已有
   - grepSearch 搜索自定义异常类（`extends RuntimeException` 或 `extends Exception`）
   - readFile 读取现有异常处理器（如果有）了解处理风格
2. **确认返回格式**：
   - readFile 读取项目的统一返回体（如 `ApiResult` / `ResponseEntity`）
   - 确认错误码体系（数字 / 枚举 / 字符串）
3. **创建异常处理器**：
   - 如果没有 → writeFile 创建 `GlobalExceptionHandler`（`@RestControllerAdvice`）
   - 如果有 → editFile 添加新的 `@ExceptionHandler` 方法
4. **添加处理方法**（按优先级）：
   - `MethodArgumentNotValidException` → 400（参数校验失败）
   - `MissingServletRequestParameterException` → 400（参数缺失）
   - `IllegalArgumentException` → 400（非法参数）
   - 自定义业务异常（如 `BusinessException`）→ 对应错误码
   - `IOException` → 500（IO 异常）
   - `Exception` → 500（兜底）
5. **运行测试**：executeShell("mvn test")

## 模板

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResult.error(400, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleAll(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(500).body(ApiResult.error(500, "服务器内部错误"));
    }
}
```

## 注意事项

- `@RestControllerAdvice` 只能处理 Controller 层抛出的异常
- 异步方法（@Async）的异常需要 `AsyncUncaughtExceptionHandler`
- 不要吞掉异常（catch 后什么都不做），至少要 log
