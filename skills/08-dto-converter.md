# Skill: 创建 DTO / VO 转换器

## 触发条件

用户要求创建 DTO、VO、Entity 转换、数据映射、对象拷贝、BeanUtils

## 执行步骤

1. **探查现有转换器**：
   - grepSearch 搜索 `BeanUtils.copyProperties` / `MapStruct` / `@Mapper` / `Converter` 了解项目转换风格
   - readFile 读取一个现有 DTO/VO 类了解命名规范
2. **读取 Entity**：
   - readFile 读取目标 Entity 完整代码，确认所有字段
3. **创建 DTO/VO 类**：
   - writeFile 创建对应的 DTO（入参）或 VO（出参）类
   - 只暴露必要字段（如 VO 不暴露 password / salt）
   - 如果项目用了 Lombok，加 `@Data` / `@Builder`
4. **创建转换方法**（按项目风格）：
   - **BeanUtils 风格**：在 Service 中直接 `BeanUtils.copyProperties(entity, dto)`
   - **手动转换**：创建 `XxxConverter` 工具类，手写 `toDto()` / `toEntity()` 方法
   - **MapStruct 风格**：创建 `@Mapper` 接口，定义 `toDto()` / `toEntity()` 抽象方法
5. **集成到 Service**：
   - 在 Service 层的 CRUD 方法中使用转换器
   - 查询返回 VO，创建/更新接收 DTO
6. **运行测试**：executeShell("mvn test")

## 手动转换器模板

```java
public class UserConverter {
    public static UserVO toVO(User entity) {
        if (entity == null) return null;
        UserVO vo = new UserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        // 不暴露 password
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    public static User toEntity(UserDTO dto) {
        if (dto == null) return null;
        User entity = new User();
        entity.setUsername(dto.getUsername());
        entity.setEmail(dto.getEmail());
        return entity;
    }
}
```

## 注意事项

- VO 绝不暴露敏感字段（密码、盐值、密钥）
- DTO 可加校验注解（@NotBlank、@Size）
- 如果字段类型不同（如 Entity 用 Long，VO 用 String），需要手动转换
