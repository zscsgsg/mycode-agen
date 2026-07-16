# Skill: 创建 CRUD Service 层

## 触发条件

用户要求新增增删改查功能、CRUD、创建 Service、数据操作层

## 执行步骤

1. **探查数据层**：
   - grepSearch 搜索 `@Mapper` 或 `extends BaseMapper` 找到 MyBatis-Plus Mapper
   - 或 grepSearch 搜索 `@Repository` / `extends JpaRepository` 找到 JPA Repository
   - readFile 读取一个现有 Mapper/Repository 了解项目 ORM 框架
2. **探查 Entity**：
   - grepSearch 搜索 `@TableName` (MyBatis-Plus) 或 `@Entity` (JPA) 找到实体类
   - readFile 读取确认字段定义和注解
3. **确认项目 Service 风格**：
   - readFile 读取一个现有 Service，学习：
     - 接口+实现 vs 直接实现类
     - 注解（@Service / @Transactional）
     - 方法命名（getXxx / findXxx / queryXxx）
     - 返回值（直接返回 Entity / 返回 VO / 返回 ApiResult）
4. **创建 Service 接口**（如果项目用接口+实现）：
   - writeFile 创建 IXxxService 接口，定义 create/read/update/delete 方法
5. **创建 Service 实现**：
   - writeFile 创建 XxxServiceImpl，注入 Mapper/Repository
   - 实现 CRUD 方法，遵循项目事务管理风格
6. **可选：创建 Controller**（如果还没有）：
   - 按 `02-rest-api` Skill 的流程创建 REST 接口
7. **运行测试**：executeShell("mvn test")

## CRUD 方法模板（MyBatis-Plus）

```java
@Service
@RequiredArgsConstructor
public class XxxServiceImpl implements IXxxService {
    private final XxxMapper xxxMapper;

    public Xxx create(Xxx entity) {
        xxxMapper.insert(entity);
        return entity;
    }

    public Xxx getById(Long id) {
        return xxxMapper.selectById(id);
    }

    public List<Xxx> list() {
        return xxxMapper.selectList(null);
    }

    public void update(Xxx entity) {
        xxxMapper.updateById(entity);
    }

    public void delete(Long id) {
        xxxMapper.deleteById(id);
    }
}
```

## 注意事项

- 如果项目用了 Lombok，Service 类用 `@RequiredArgsConstructor` 而非 `@Autowired`
- 如果项目用了 `@Transactional`，写操作方法也要加
- 分页查询：如果项目用了 MyBatis-Plus 的 `Page<T>`，分页方法也要用
