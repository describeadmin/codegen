# codegen

以 YAML 为输入的代码生成器，产出**薄**业务代码。

**从零开始用这套框架，请看
[快速开始](https://github.com/describeadmin/docs/blob/main/QUICKSTART.md)。**

## 获取

从 [Releases](https://github.com/describeadmin/codegen/releases) 下载 `codegen.jar`
（附带 `.sha256` 校验和）。

`codegen` **不发布到 Maven Central，也绝不应出现在业务方 `pom.xml` 的
`<dependencies>` 中**——它是开发期命令行工具，产物一旦生成即脱离生成器，
业务方运行时完全不需要它。

## 为什么以 YAML 为输入，而不读数据库元数据

这是方案 3.3.1 的一处优先级调整，原因有两条：

1. **各国产化数据库的 `information_schema` 差异极大**——表结构、类型映射、注释字段都不一致。
   读元数据的路子恰恰在最需要生成器的场景（达梦 / 金仓 / OceanBase）下不可用。
2. **业主环境往往连不上**。政务项目中开发机通常无法直连业主的生产/测试库，
   "以数据库为输入"这个前提本身不成立。

对 AI Agent 还有一个额外好处：写一份受校验约束的 YAML，比直接写四个 Java 文件的出错面小得多，
且错误在解析阶段就被明确指出，不必等编译或运行。

## 用法

```bash
mvn package                                   # 产出 target/codegen.jar
java -jar target/codegen.jar <spec.yaml> [选项]
```

| 选项 | 说明 |
|---|---|
| `--out DIR` | 后端输出根目录，默认当前目录 |
| `--frontend-out DIR` | 前端输出根目录，默认 `<out>/frontend` |
| `--layout nested\|flat` | 后端 Java 包布局，默认 `nested`（见下文「包布局」） |
| `--force` | 覆盖已存在的文件（默认跳过） |
| `--dry-run` | 只打印将生成的文件，不写盘 |

**默认不覆盖已有文件**是有意为之：Service 与 Controller 是业务方会手工添加逻辑的地方，
重新生成时冲掉它们不可接受。

## 产出

**后端**（`<layer>` = `entity` / `mapper` / `service` / `controller`）

| 文件 | 说明 |
|---|---|
| `src/main/java/<pkg>/<module>/<layer>/<Entity>*.java` | `nested` 布局（默认） |
| `src/main/java/<pkg>/<layer>/<Entity>*.java` | `flat` 布局（`--layout flat`） |
| `.../entity/<Entity>Entity.java` | 继承 `BaseEntity` |
| `.../mapper/<Entity>Mapper.java` | 继承 `BaseMapper` |
| `.../service/<Entity>Service.java` | 继承 `BaseService` |
| `.../controller/<Entity>Controller.java` | 继承 `BaseController`，含五个标准端点；有 `query` 字段时覆写 `buildListWrapper` |
| `src/main/resources/db/schema-<table>.sql` | 5.7 安全子集的建表 DDL |
| `src/main/resources/db/menu-<table>.sql` | **菜单与按钮权限点** |

**前端**

| 文件 | 说明 |
|---|---|
| `src/api/<module>.ts` | 类型定义与四个接口封装 |
| `src/views/<module>/index.vue` | 列表 + 搜索栏 + 表单弹窗 + 受控删除确认 |

### 为什么菜单 SQL 不能少

前端 `accessMode: 'backend'`，路由完全由 `sys_menu` 表下发。
**只生成 `.vue` 文件而没有菜单行，页面在系统里根本不可达**——访问对应路径会落到 404，
侧边栏也不会出现入口。这一条在本项目实测踩过，所以由生成器一并产出。

### 关于 data-testid

页面所有关键交互元素都带 `data-testid`（命名 `<模块>-<对象>-<动作>`，见 CLAUDE.md 4.4），
供 AI 自动化测试定位。命名规则由 `VueGenerator` 单点提供。

## 包布局

后端 Java 文件默认落在 `<basePackage>.<module>.<layer>`（`nested`）。只有少量模块、
不想要模块层级的小工程可以切到 `flat`——`<basePackage>.<layer>`：

| 布局 | Controller 包名 |
|---|---|
| `nested`（默认） | `com.example.demo.project.controller` |
| `flat` | `com.example.demo.controller` |

`flat` **只影响后端 Java 包与文件落点**。前端目录、`schema-*.sql` / `menu-*.sql`、
权限点前缀、`@RequestMapping` 路径一律以模块名 / 表名为准，不变。

取值优先级（命中即用）：

1. `--layout <nested|flat>` 命令行参数
2. spec 顶层 `layout:` 键
3. `CODEGEN_LAYOUT` 环境变量（给工作空间设项目级默认）
4. 内置默认 `nested`

非法取值在任何一层都会 fail fast 并点明来源。切到非默认布局时，生成输出会多打一行
`布局: flat（来自 …）`。

> ⚠️ 生成器**从不删文件**。中途从 `nested` 切到 `flat`，旧的 `<module>/` 子包不会被清掉，
> 需手工删除。布局最好开工即定。

## Spec 格式

见 `examples/project.yaml`。

### 字段类型

类型集合刻意受限——**SQL 红线固化在类型系统里，生成器根本产不出违规 SQL**：

| type | Java | MySQL |
|---|---|---|
| `string` | `String` | `VARCHAR(length)` |
| `text` | `String` | `TEXT` |
| `int` | `Integer` | `INT` |
| `long` | `Long` | `BIGINT` |
| `decimal` | `BigDecimal` | `DECIMAL(precision,scale)` |
| `flag` | `Integer` | `TINYINT`（0/1 语义） |
| `date` | `LocalDate` | `DATE` |
| `datetime` | `LocalDateTime` | `DATETIME` |

**刻意不提供**的类型及原因：

| 写了会报错 | 原因 | 改用 |
|---|---|---|
| `timestamp` | 2038 年上限，自动更新语义在 5.7 与各国产化库上不一致 | `datetime` |
| `boolean` | MySQL 中实为 `TINYINT(1)` 别名，各库处理不一致 | `flag` |
| `json` | JSON 函数集在 5.7 与国产化库上差异大 | `text` + 应用层序列化 |
| `float` / `double` | 金额场景会丢精度 | `decimal` |

### 字段属性

| 属性 | 默认 | 说明 |
|---|---|---|
| `name` | 必填 | Java 字段名（小驼峰） |
| `column` | 由 `name` 推导 | 列名（下划线） |
| `type` | 必填 | 见上表 |
| `length` / `precision` / `scale` | 255 / 18 / 2 | 长度与精度 |
| `nullable` | `true` | 是否可空 |
| `comment` | 同 `name` | 列注释，同时用作前端表单标签与测试用例描述 |
| `query` | `none` | 列表查询方式：`none` / `eq` / `like` / `range` |
| `indexed` | `false` | 是否建索引 |

## 校验规则

校验**一次性报出全部问题**，而不是遇到第一个就中断——修一轮改完，不必来回七八次。
每条错误都指明位置与修法。会被拦下的情况：

- 字段名与 `BaseEntity` 内置字段重名（`id`/`createTime`/`deleted`/`version` 等）
- 表名使用 `sys_` 前缀（那是 `framework-system-starter` 的系统管理表）
- `entity` 带 `Entity` 后缀（生成器会自动加）
- 索引列 `VARCHAR` 超过 191 字符（utf8mb4 下会突破 MySQL 5.7 的 767 字节键长上限）
- `TEXT` 列建索引
- 非字符串类型使用 `query: like`
- 字段名或列名重复
- 使用被排除的类型（会附带替代方案）

## 查询条件

spec 里的 `query` 会生成**真正生效的**筛选：Controller 覆写框架的 `buildListWrapper`，
按 `like` / `eq` / `range` 拼 `QueryWrapper`。

- 空值不参与筛选。否则「不填任何条件」会退化成 `WHERE col = ''`，一条都查不出
- `like` 一律**右模糊**（`'x%'`），可走索引；不生成左模糊以免全表扫描
- 参数转换失败返回 `BAD_REQUEST` 而不是服务器错误——「日期填错了」是使用者的输入问题

⚠️ 不要在生成的 Controller 里另写一个带 `@GetMapping` 的 `list(...)` 重载：
它与基类的 `list` 撞在同一个 GET 路径上，Spring 启动时直接报 Ambiguous mapping。
框架为此留了 `buildListWrapper` 这一个覆写点。

## 已验证

另有一轮**真实环境端到端验收**（`sample-app` + `frontend` + MySQL 5.7）：

- 生成的 DDL 在 **MySQL 5.7 上实际执行通过**，中文注释完好、排序规则 `utf8mb4_general_ci`、时间列 `datetime`
- 生成的四个 Java 文件在**真实框架依赖树下编译通过**，`sample-app` 全量 34/34
- 生成的 `.vue` 与 `.ts` 通过项目的 `oxfmt` / `eslint` / `vue-tsc`，且**与格式化结果逐字节一致**
- **浏览器实跑 9/9**：菜单出现在侧边栏 → 页面打开 → 新增 → 搜索命中且筛掉不匹配项 → 重置 → 删除
- 数据库侧复核：`create_by` 由框架填充，删除是逻辑删除（`deleted=1`，物理行保留）

`sample-app` 的 `project` 模块**没有一行手写代码**，全部由 `codegen-specs/project.yaml` 生成。
