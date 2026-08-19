# codegen

以 YAML 为输入的代码生成器，产出**薄**业务代码。

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
| `--out DIR` | 输出根目录，默认当前目录 |
| `--force` | 覆盖已存在的文件（默认跳过） |
| `--dry-run` | 只打印将生成的文件，不写盘 |

**默认不覆盖已有文件**是有意为之：Service 与 Controller 是业务方会手工添加逻辑的地方，
重新生成时冲掉它们不可接受。

## 产出

| 文件 | 说明 |
|---|---|
| `src/main/java/<pkg>/<module>/entity/<Entity>Entity.java` | 继承 `BaseEntity` |
| `.../mapper/<Entity>Mapper.java` | 继承 `BaseMapper` |
| `.../service/<Entity>Service.java` | 继承 `BaseService` |
| `.../controller/<Entity>Controller.java` | 继承 `BaseController`，含五个标准端点 |
| `src/main/resources/db/schema-<table>.sql` | 5.7 安全子集的建表 DDL |
| `test-specs/<module>.yaml` | **结构化端到端验收用例** |

最后一项是关键：代码与它的验收用例一起生成，AI Agent 拿到就能直接执行端到端验证，
把「面向 AI 编程」与「AI 自主端到端测试」两个目标连起来，而不是等人事后补测试。

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

## 已验证

- 生成的 DDL 在 **MySQL 5.7 上实际执行通过**，中文注释完好、排序规则为 `utf8mb4_general_ci`、时间列为 `datetime`
- 生成的四个 Java 文件在**真实框架依赖树下编译通过**
- 生成的测试 Spec 是**合法 YAML**（有专门的测试用 SnakeYAML 解析产物，防住缩进与引号错误）
