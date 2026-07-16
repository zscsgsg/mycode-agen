# Skill: 添加安全认证（JWT / Spring Security）

## 触发条件

用户要求添加登录、认证、JWT、Token、Spring Security、权限控制、拦截器

## 执行步骤

1. **探查现有安全机制**：
   - grepSearch 搜索 `@EnableWebSecurity` / `SecurityFilterChain` / `JwtTokenProvider` 看是否已有
   - grepSearch 搜索 `@PreAuthorize` / `@RolesAllowed` 看是否用了方法级权限
   - readFile 读取 `pom.xml` 确认是否引入了 `spring-boot-starter-security`
2. **确认认证方式**：
   - 如果项目已有 Security 配置 → 读取了解风格，按现有方式扩展
   - 如果项目没有 → 按以下流程从零搭建
3. **创建用户实体**（如果没有）：
   - Entity（User / SysUser）+ Mapper + Service
   - 字段：id, username, password, role, enabled
4. **创建 JWT 工具类**：
   - 生成 Token（userId + role → JWT）
   - 解析 Token（JWT → userId + role）
   - 刷新 Token（过期前刷新）
5. **创建认证过滤器**：
   - `JwtAuthenticationFilter extends OncePerRequestFilter`
   - 从 Header 提取 Token → 解析 → 设置 SecurityContext
6. **创建 Security 配置**：
   - `SecurityFilterChain` Bean
   - 放行路径（登录、注册、Swagger）
   - 认证路径（其余全部）
7. **创建登录接口**：
   - POST `/auth/login` → 验证密码 → 返回 JWT
8. **运行测试**：executeShell("mvn test")

## 注意事项

- 密码必须用 BCrypt 加密，绝不存明文
- JWT 密钥从 `application.yml` 读取，不硬编码
- Token 过期时间建议 2 小时，配合刷新 Token 机制
- 如果是简单项目，也可以用拦截器（HandlerInterceptor）+ 自定义 Token 替代 Spring Security
