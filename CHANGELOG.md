# 更新日志

版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)，
每个版本固定分 **Breaking Changes / New Features / Bug Fixes** 三类。

`codegen` 的版本号与 `framework` 保持一致：它生成的代码要继承框架基类，
两者的兼容性必须成对理解。

## 未发布（开发中）

> `pom.xml` 里的版本仍是 `0.1.1`，尚未跟随 framework 升到 `0.2.0-SNAPSHOT`
> ——本文件开头那条"版本号与 framework 保持一致"的约定当前是断开的，发版前需要对齐。

### Breaking Changes

- **`long` 类型字段生成的 TypeScript 类型由 `number` 改为 `string`**，
  连带审计字段 `id`/`createBy`/`updateBy` 与 `update*Api(id)`/`delete*Api(id)`
  的形参、`editingId`/`deletingId` 的 ref 类型。
  框架 0.2.0 起把所有 `Long` 序列化成字符串（避免 19 位雪花 ID 被 JS 舍入），
  生成的前端必须跟着变，否则类型与实际返回值对不上。
  `version` 是 `Integer`，不在此列；`%sPage` 的 `total`/`pages`/`current`/`size`
  也保持 `number`（框架用 `@JsonFormat(shape = NUMBER)` 排除了它们）。
- **`long` 字段的表单控件由 `ElInputNumber` 改为 `ElInput`**。
  v-model 现在是字符串，数字微调器接不住；而本框架里的 `long` 基本都是 ID 引用
  （`ownerDeptId` 这类），微调器本就不是合适的控件。
  搜索栏与表单初值同步改为空串。

### New Features

- 生成的 Controller 带 `permPrefix()` 显式覆写，消除模块名含下划线时
  权限点静默错配、连 ADMIN 都被 403 的问题。

### Bug Fixes

无。

---

## 0.1.1 (2026-08-20)

**本版本没有任何功能变更。** 它跟随 `framework` 0.1.1 走，只为维持本文件开头那条约定——
版本号与 `framework` 一致。framework 0.1.1 交付的是业务方脚手架
`describeadmin-archetype`，与生成器无关。

### Breaking Changes

无。

### New Features

无。

### Bug Fixes

无。

---

## 0.1.0 (2026-08-20)

首个公开版本。

### Breaking Changes

无——首个版本。

### New Features

**以 YAML spec 为输入，一次生成前后端两侧的产物**

| 生成器 | 产出 |
|---|---|
| `JavaGenerator` | Entity / Mapper / Service / Controller 四件套，全部继承框架基类 |
| `DdlGenerator` | `schema-<表名>.sql`，MySQL 5.7 安全子集，显式声明字符集与排序规则 |
| `MenuSqlGenerator` | `menu-<表名>.sql`，菜单与按钮级权限点。没有它，生成的页面在系统里不可达 |
| `VueGenerator` | 列表页 `.vue` 与 API 封装 `.ts` |
| `TestSpecGenerator` | 结构化验收用例 spec |

**不读数据库元数据，以 YAML 为输入**

理由不是省事，而是可行性：各国产化数据库的 `information_schema` 差异极大，
读元数据的路子恰恰在最需要生成器的场景下不可用；且政务项目中开发机
通常无法直连业主的库，"以数据库为输入"这个前提本身不成立。

对 AI Agent 的额外好处：写一份受校验约束的 YAML，出错面比直接写四个
Java 文件小得多，且错误在解析阶段就被明确指出，不必等编译或运行。

**分发形态：GitHub Release 附带可执行 fat jar**

`codegen` **不发布到 Maven Central，也绝不应出现在业务方 `pom.xml` 的
`<dependencies>` 中**——它是开发期命令行工具，产物一旦生成即脱离生成器，
业务方运行时完全不需要它。

### Bug Fixes

- 生成的 Vue 页面此前 emit `import ConfirmDialog from '#/components/confirm-dialog.vue'`，
  等于要求每个业务方仓库在那个确切路径上有那个确切文件，而这条要求
  没有任何地方写过。现改为从 `@describeadmin/ele-ui` 导入，隐藏契约消除。
- CI 中 MySQL 5.7 的就绪探针原先用走 unix socket 的 `mysqladmin ping`。
  5.7 的 entrypoint 初始化期间会先起一个带 `--skip-networking` 的临时服务器，
  socket 上的 ping 立即成功，但 root 口令尚未设置，随后的查询必然
  `ERROR 1045 Access denied`。改为用带认证的查询作为探针。

### 已知限制

- **Maven 插件形态（`describeadmin-codegen-maven-plugin`）尚未交付。**
  设计上它才是主形态——写在 `pom.xml` 里是可被 AI Agent 发现的，
  而一条需要外部记忆的 `java -jar` 命令不具备这个性质。当前只有 fat jar 形态。
- 生成产物覆盖单表 CRUD；关联表、树形结构等需手工调整。
