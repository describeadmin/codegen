# codegen

describeadmin 的代码生成器：读一份 YAML，同时生成一个业务模块的后端（Entity / Mapper / Service / Controller）、
数据库 changelog（建表 + 菜单与权限点）和前端（列表页 + 接口封装）。生成的是继承框架基类的「薄」代码，框架升级时基本不用动。

**文档：<https://describeadmin.github.io/docs/>**。教程见 [加第一个业务模块](https://describeadmin.github.io/docs/guide/first-module)，
spec 全部字段、命令行参数、校验规则见 [codegen YAML spec](https://describeadmin.github.io/docs/reference/codegen-spec)。

## 获取

从 [Releases](https://github.com/describeadmin/codegen/releases) 下载 `codegen.jar` 与 `codegen.jar.sha256`，
**版本与业务工程的框架版本相同**。

codegen 是开发期的命令行工具，**不发布到 Maven Central，不要加进业务工程的依赖**：代码生成之后就和它无关了。

## 最小示例

```yaml
basePackage: com.acme.myserver
module: project
entity: Project
table: biz_project
comment: 项目
fields:
  - name: projectName
    type: string
    length: 128
    nullable: false
    comment: 项目名称
    query: like
    indexed: true
```

```bash
java -jar codegen.jar project.yaml --out . --frontend-out ../my-web --dry-run   # 先看会生成什么
java -jar codegen.jar project.yaml --out . --frontend-out ../my-web
```

完整示例见 [`examples/project.yaml`](examples/project.yaml)。

## 构建

```bash
mvn clean verify          # 任意 JDK ≥ 17；产出 target/codegen.jar（fat jar）
java -jar target/codegen.jar examples/project.yaml --out /tmp/gen
```

CI（`.github/workflows/ci.yml`）：

- `build`：单元测试，用 `examples/project.yaml` 生成全部文件并逐个断言，建表 DDL 在 MySQL 5.7 上实际执行并按具体值核对中文注释
- `e2e-compile`：用 `describeadmin-archetype` 现生成一个业务工程，把生成的模块放进去 `mvn test-compile`，确认对真实的框架基类编译通过

打 `v*` tag 触发 `release.yml`，产出随 GitHub Release 分发的 `codegen.jar` 与 `.sha256`。

参与开发前读 docs 仓的 [`CLAUDE.md`](https://github.com/describeadmin/docs/blob/main/CLAUDE.md) 与
[架构与关键决策](https://describeadmin.github.io/docs/dev/architecture#codegen)。

## 变更记录与许可证

[CHANGELOG.md](./CHANGELOG.md) · [Apache-2.0](./LICENSE)
