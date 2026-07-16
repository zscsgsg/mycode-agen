# Skill: 重构方法

## 触发条件

用户要求重构、优化、改进、简化、清理某个方法或类

## 执行步骤

1. **精读目标代码**：
   - readFile 读取目标方法/类的完整代码
   - 识别重构目标：减少重复、提高可读性、拆分过长方法、消除魔法值
2. **分析影响范围**：
   - grepSearch 搜索方法名/类名，找出所有调用点
   - readFile 读取调用者代码，确认重构后不会破坏调用方
3. **制定重构方案**：
   - 提取公共方法（Extract Method）
   - 引入参数对象（Introduce Parameter Object）
   - 用枚举替代字符串常量（Replace Magic String with Enum）
   - 简化条件逻辑（Replace Conditional with Polymorphism / Strategy）
4. **执行重构**：
   - 用 editFile 逐步修改，保持每次修改小且可验证
   - 如果重构涉及多个文件，按依赖顺序从底层改起
5. **更新调用方**：如果方法签名变了，grepSearch 找到所有调用点并更新
6. **运行测试**：executeShell("mvn test") 确认不破坏现有功能

## 常见重构模式

| 模式                          | 适用场景                            |
| ----------------------------- | ----------------------------------- |
| Extract Method                | 方法超过 50 行，包含多个逻辑块      |
| Inline Method                 | 方法只有 1-2 行且只被调用 1 次      |
| Replace Magic Number          | 代码中有 86400、3.14 等无注释的数字 |
| Introduce Explaining Variable | 复杂条件表达式 `if (a && b \|\| c)` |
| Replace Temp with Query       | 临时变量只用 1 次且可被方法替代     |

## 注意事项

- 重构不改变外部行为，只改变内部结构
- 每次 editFile 只做一个逻辑变更，便于回滚
- 如果不确定是否安全，先 grepSearch 确认所有调用点
