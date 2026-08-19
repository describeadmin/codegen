# describeadmin 编码规范与项目约定

> 本文件是组织级的唯一约定来源，供开发者与 AI Agent 共同遵循。
> 本文件由组织根仓库维护，各子仓库以副本或软链复用，**不要在子仓库中单独修改**。
> （自动同步脚本待实现，当前手工同步。）
>
> 设计依据见 `develop_plan.md`；版本事实见 `VERSION_BASELINE.md`。
> 本文件只写"必须遵守的约定"，不重复方案里的论证过程。

---

## 0. 先读这里

本项目是一个**平台/SDK**，不是可复制粘贴的模板。任何改动前先确认自己在哪一层：

- **Platform 层**（`framework`、`framework-*-starter`、`codegen`）——框架团队维护，版本化发布，改动要考虑向后兼容
- **Business 层**——业务方维护，只通过 Maven/npm 依赖引用 Platform 层

**绝对禁止**：把框架源码拷贝进业务仓库；在业务仓库里修改框架代码。

多仓拓扑下，改动一个 SPI 接口通常要同步改 `framework` + 对应 `framework-ext-*` + `frontend`。
动手前先跑 `./scripts/clone-all.sh` 拿到完整上下文。

---

## 1. 版本与环境（硬约束）

| 项 | 取值 | 说明 |
|---|---|---|
| 构建 JDK | **21** | 通过 Maven Toolchains 指定，不依赖 `PATH` |
| 编译目标 | **`maven.compiler.release=17`** | 产物必须能在 Java 17 上运行 |
| Spring Boot | **3.5.16** | 不要升级到 4.x，理由见方案 2.2.1 |
| Jackson | **2.x**（`com.fasterxml.jackson.*`） | 不要使用 `tools.jackson.*`（那是 Jackson 3） |
| Node / pnpm | node `^22.18 \|\| ^24.12`，pnpm `>=11` | |

**不要用 `java -version` 判断构建 JDK**——本项目通过 toolchains 选择 JDK，
`PATH` 上是什么与构建用什么无关。首次配置见 `scripts/toolchains.xml.sample`。

**版本核查纪律**：任何依赖版本以 `https://repo1.maven.org/maven2/**/maven-metadata.xml`
和 `https://registry.npmjs.org/<pkg>` 为准。
**禁止使用 `search.maven.org/solrsearch`**——该索引已陈旧，且会对真实存在的制品返回 `numFound=0`。

---

## 2. 命名规范

| 场景 | 规则 | 示例 |
|---|---|---|
| GitHub 组织 | `describeadmin` | https://github.com/describeadmin |
| Maven groupId | `io.github.describeadmin` | 已在 Central Portal 完成命名空间验证 |
| **Java 包名** | `io.github.describeadmin.*` | 与 groupId **完全一致** |
| Maven artifactId | `framework-<能力>-starter` | `framework-notify-dingtalk-starter` |
| npm 组织 / 包 | `@describeadmin/<能力>` | `@describeadmin/ui` |
| Spring 配置前缀 | `describeadmin.<模块>` | `describeadmin.web.trace.enabled` |

全部标识符统一为 `describeadmin`（无连字符），groupId 与 Java 包名一一对应，无需做任何映射。

包结构约定（每个 starter 内部）：

```
io.github.describeadmin.<模块>
├── autoconfigure/   自动配置类、@ConfigurationProperties
├── api/             对外 SPI 接口与 DTO —— 属于兼容性承诺范围，改动需走 SemVer
├── core/            内部实现
└── util/            工具类
```

`api/` 包下的任何 public 签名变更都是 Breaking Change。

---

## 3. 数据库约定（强制）

框架产出的 DDL、基类 SQL、生成器模板都会跑在业主的数据库上，业主可能用
MySQL 5.7 或宣称兼容 5.7 的国产化库（达梦 / 金仓 / OceanBase）。因此：

### 3.1 SQL 红线（框架代码与生成器模板必须遵守）

禁止使用：

- 窗口函数（`ROW_NUMBER()`、`RANK()`、`OVER(...)`）
- CTE（`WITH ... AS`）
- 函数索引、不可见列、生成列
- `JSON_TABLE` 及 8.0+ 新增 JSON 函数
- 多列 `ON UPDATE CURRENT_TIMESTAMP`
- 依赖 CHECK 约束的实际生效行为

基线是 **MySQL 5.7 语法的安全子集**——国产化库的 MySQL 兼容通常是子集而非超集，
按 5.7 全集写仍可能失败。

### 3.2 建表规范

```sql
CREATE TABLE sys_example (
  ...
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
```

`CHARACTER SET` 与 `COLLATE` **必须显式声明**，不依赖服务器默认值
（5.7 默认 `utf8mb4_general_ci`，8.0+ 默认 `utf8mb4_0900_ai_ci`，国产化库各不相同）。

索引键长度按 5.7 的保守限制设计。

### 3.3 主键

默认数据库自增（`IdType.AUTO`），通过
`mybatis-plus.global-config.db-config.id-type` 可切换为雪花 ID。
**不要在实体类上硬编码 `@TableId(type = ...)`**，让全局配置生效。

### 3.4 JDBC 驱动

**框架的任何模块都不得引入 JDBC 驱动依赖**（含 `runtime` scope）。
驱动由业务方在自己的 `pom.xml` 中声明。
`framework-bom` 只提供 `mysql-connector-j` 的 5.7-safe 默认版本 `8.2.0`，业务方可覆盖。

> ⚠️ Connector/J 自 **8.3.0** 起官方不再支持 MySQL 5.7（"8.0 and later"）。
> **8.2.0 是最后一个支持 5.7 的版本**。不要"顺手升级"这个默认值。

### 3.5 分页

MyBatis-Plus 的 `DbType` 必须是配置项，**不要硬编码 `DbType.MYSQL`**。

### 3.6 ⚠️ 字符集：任何读取 SQL/数据文件的环节都必须显式指定编码

本项目已在**两个不同入口**因这一条踩坑（见 VERSION_BASELINE.md 发现 ④、⑤）：

| 入口 | 默认行为 | 必须显式指定 |
|---|---|---|
| `mysql` CLI 导入脚本 | 使用客户端默认字符集 | `--default-character-set=utf8mb4` |
| Spring `spring.sql.init` | 使用**平台默认编码**（中文 Windows 为 GBK） | `spring.sql.init.encoding=UTF-8` |
| 测试 JVM | 平台默认编码 | surefire `-Dfile.encoding=UTF-8` |

**症状极具欺骗性**：中文全部写成乱码，但 `COUNT(*)` 校验完全正常，环境看起来健康。

由此引出一条测试规范——**断言要比对具体值，不要只比对行数**：

```java
// ❌ 查不出字符集问题
assertThat(count).isEqualTo(1);
// ✅
assertThat(nickname).isEqualTo("超级管理员");
```

这与方案 5.4 节"不能仅凭页面看起来正常就下结论"是同一类问题，只是发生在数据层。

### 3.7 ⚠️ MyBatis-Plus 3.5.17 的包路径陷阱（AI 极易写错）

网上绝大多数 MyBatis-Plus 资料、以及 AI 依据旧语料生成的代码，用的都是**旧包路径**。
3.5.x 后期做过两次拆分，以下两条**必须按实测结果写**：

| 类 | ❌ 旧路径（绝大多数资料/AI 输出） | ✅ 3.5.17 实际路径 | 所在制品 |
|---|---|---|---|
| `IService` / `ServiceImpl` | `com.baomidou.mybatisplus.extension.service[.impl]` | `com.baomidou.mybatisplus.spring.service[.impl]` | `mybatis-plus-spring` |
| `PaginationInnerInterceptor` | `...extension.plugins.inner`（仍在此包名下） | 包名不变，但**已移出 `mybatis-plus-extension` 制品** | `mybatis-plus-jsqlparser` |

后者的症状是编译报 `找不到符号: 类 PaginationInnerInterceptor`——包名看着对，
是**制品**没引。`framework-mybatis-starter` 已显式引入 `mybatis-plus-jsqlparser`。

**核实方法**（不要靠记忆或搜索结果）：

```bash
unzip -l ~/.m2/repository/com/baomidou/<制品>/3.5.17/<制品>-3.5.17.jar | grep '<类名>.class'
```

业务代码继承框架的 `BaseService` / `BaseController` 即可，正常情况下不需要直接 import 这些类。

---

## 4. 代码约定

### 4.1 分层与基类

生成器与业务代码产出的是**"薄"代码**，通用逻辑留在框架基类：

```java
public class DeptEntity extends BaseEntity { ... }
public interface DeptMapper extends BaseMapper<DeptEntity> { }
public class DeptService extends BaseService<DeptMapper, DeptEntity> { ... }
public class DeptController extends BaseController<DeptService, DeptEntity> { ... }
```

审计字段（创建人/创建时间/更新人/更新时间/逻辑删除）由 `BaseEntity` 统一承担，
**不要在业务实体里重复定义**。

### 4.2 SPI 扩展

新增登录方式或消息通道时，实现对应接口并注册为 Bean 即可，
框架通过 `List<AuthProvider>` / `Map<String, NotifyChannel>` 自动收集：

```java
public interface AuthProvider {
    String type();
    boolean supports(String type);
    LoginUser authenticate(AuthRequest request);
}
```

**框架核心代码里不允许出现任何具体实现的名字**（"zhengwuding"、"dingtalk" 等字符串
只能出现在对应的 ext 模块内）。

### 4.3 返回值

Controller 一律返回 `Result<T>`，不要自行拼装响应结构。
异常交给全局异常处理器，不要在 Controller 里 try-catch 后返回错误码。

### 4.4 前端测试锚点

所有关键交互元素必须带 `data-testid`，供 AI 自动化测试定位：

```vue
<el-button data-testid="dept-add-btn">新增</el-button>
```

命名格式：`<模块>-<对象>-<动作>`。没有 `data-testid` 的交互元素视为未完成。

---

## 5. 兼容性与发布

- 遵循 SemVer：只有大版本允许破坏性变更
- 废弃接口先标 `@Deprecated`，至少保留 1–2 个小版本
- `api/` 包下的 public 签名 = 兼容性承诺范围
- 每次发版的 CHANGELOG 必须分 Breaking Changes / New Features / Bug Fixes 三类

---

## 6. 常用命令

```bash
# 拉取全部仓库到工作区
./scripts/clone-all.sh

# 构建（依赖 toolchains，与 PATH 上的 java 无关）
mvn -f framework/pom.xml clean install

# 跳过 GPG 签名的本地安装
mvn -f framework/pom.xml clean install -Dgpg.skip=true

# 验证 release profile 能产出 Central 要求的三件套（跳过签名，本地可跑）
mvn -f framework/pom.xml clean verify -Prelease -Dgpg.skip=true
```

**发布相关操作一律参照 `docs/RELEASE.md`**，不要凭记忆执行——
发到 Maven Central 的版本不可撤回、不可覆盖。

---

## 7. 给 AI Agent 的额外提示

- **改动前先读 `develop_plan.md` 对应章节**，本文件只写结论不写理由，理由在方案里
- 遇到版本问题查 `VERSION_BASELINE.md`，里面记录了已核验的事实和已知的错误信息源
- 本项目多处决策是**有意选择上一代技术**（Spring Boot 3.5 而非 4.x、Jackson 2 而非 3），
  这些不是"过时待升级"，不要主动"帮忙升级"
- 同理，`mysql-connector-j` 的 `8.2.0` 默认值是**有意钉住**的（8.3.0 起不再支持 MySQL 5.7），不要升级
- 发布相关操作参照 `docs/RELEASE.md`，不要凭记忆执行
