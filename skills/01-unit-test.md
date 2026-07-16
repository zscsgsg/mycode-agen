# Skill: 写 JUnit5 单元测试

## 触发条件

用户要求为某个类/方法编写单元测试、unit test、测试用例、test case

## 执行步骤

1. **定位目标类**：用 getRepoMap 找到目标类所在包，用 readFile 读取完整代码
2. **分析可测逻辑**：识别所有 public 方法，分析每个方法的：
   - 正常输入 → 期望输出
   - 边界值（null、空集合、极值）
   - 异常路径（参数非法、依赖为空）
3. **创建测试文件**：用 writeFile 在 `src/test/java/` 对应包下创建测试类
   - 文件第一行必须是 `package 与源文件相同的包名;`
   - 导入 JUnit 5：`import org.junit.jupiter.api.Test;` + `import static org.junit.jupiter.api.Assertions.*;`
   - 如果项目用了 Lombok，测试类也可以用 `@Data` 等
4. **编写测试方法**：
   - 命名规范：`test_方法名_场景描述()`（如 `test_login_nullPassword_throwsException`）
   - 每个测试方法只测一个行为（AAA 模式：Arrange-Act-Assert）
   - 正常值至少 1 个、边界值至少 1 个、异常值至少 1 个
5. **构造测试数据**：通过构造器或 setter 设置字段值，不需要 mock 框架
6. **运行验证**：executeShell("mvn test -Dtest=测试类名") 确认全部通过

## 模板

```java
package com.example.service;  // 必须与源文件包名一致

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XxxServiceTest {

    @Test
    void test_方法名_正常场景() {
        XxxService service = new XxxService();
        // Arrange
        var input = ...;
        // Act
        var result = service.方法名(input);
        // Assert
        assertEquals(expected, result);
    }

    @Test
    void test_方法名_边界值() {
        // null / 空字符串 / 空集合 / 0
    }

    @Test
    void test_方法名_异常场景() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.方法名(invalidInput);
        });
    }
}
```

## 注意事项

- 测试文件**必须包含正确的 package 声明**，否则 Maven Surefire 发现不了
- 不要 mock 外部依赖，用真实对象构造测试数据
- 如果目标方法依赖其他 Service，可以通过构造器注入一个简单的 stub 实现
