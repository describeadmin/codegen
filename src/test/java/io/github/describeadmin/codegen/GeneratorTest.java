package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.generator.DdlGenerator;
import io.github.describeadmin.codegen.generator.JavaGenerator;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("代码生成")
class GeneratorTest {

    private static ModuleSpec spec;

    @BeforeAll
    static void load() throws Exception {
        spec = SpecLoader.load(Path.of("examples/project.yaml"));
    }

    // ------------------------------------------------------------------ Java

    @Test
    @DisplayName("实体只含业务字段，不重复声明审计字段")
    void entityIsThin() {
        String src = JavaGenerator.entity(spec);
        assertThat(src).contains("extends BaseEntity");
        assertThat(src).contains("@TableName(\"biz_project\")");
        assertThat(src).contains("private String projectName;");
        assertThat(src).contains("private java.math.BigDecimal budget;".replace("java.math.", ""));
        // 审计字段与主键必须由 BaseEntity 承担
        assertThat(src).doesNotContain("private Long id;");
        assertThat(src).doesNotContain("createTime");
        assertThat(src).doesNotContain("deleted");
    }

    @Test
    @DisplayName("实体访问器由 Lombok 承担，不逐字段手写 get/set")
    void entityUsesLombokAccessors() {
        String src = JavaGenerator.entity(spec);
        assertThat(src).contains("import lombok.Getter;");
        assertThat(src).contains("import lombok.Setter;");
        assertThat(src).contains("@Getter");
        assertThat(src).contains("@Setter");
        // 一条手写访问器都不该有
        assertThat(src).doesNotContain("public String getProjectName()");
        assertThat(src).doesNotContain("public void set");
    }

    @Test
    @DisplayName("按需生成 import，不生成 java.lang 下的类型")
    void entityImports() {
        String src = JavaGenerator.entity(spec);
        assertThat(src).contains("import java.math.BigDecimal;");
        assertThat(src).contains("import java.time.LocalDate;");
        assertThat(src).doesNotContain("import String;");
        assertThat(src).doesNotContain("import java.lang.");
    }

    @Test
    @DisplayName("Service 也用 @RequiredArgsConstructor，业务加依赖时不手写构造器")
    void serviceUsesLombokConstructor() {
        String src = JavaGenerator.service(spec);
        assertThat(src).contains("import lombok.RequiredArgsConstructor;");
        assertThat(src).contains("@Service");
        assertThat(src).contains("@RequiredArgsConstructor");
        assertThat(src).contains("extends BaseService<ProjectMapper, ProjectEntity>");
        // 依赖注入构造器交给 Lombok，不再手写。空 Service 上该注解给出的是一个无参构造器，
        // 与不写注解时的隐式默认构造器等价，因此本改动对现有生成物是中性的
        assertThat(src).doesNotContain("public ProjectService(");
        // Mapper 由基类持有，生成物里不该出现自己的 mapper 字段
        assertThat(src).doesNotContain("ProjectMapper projectMapper;");
    }

    @Test
    @DisplayName("Controller 继承基类并绑定正确的泛型与路径")
    void controller() {
        String src = JavaGenerator.controller(spec);
        assertThat(src).contains(
                "extends BaseController<ProjectService, ProjectMapper, ProjectEntity>");
        assertThat(src).contains("@RequestMapping(\"/api/project\")");
        assertThat(src).contains("protected ProjectService getService()");
        // 依赖注入构造器交给 Lombok，不再手写
        assertThat(src).contains("import lombok.RequiredArgsConstructor;");
        assertThat(src).contains("@RequiredArgsConstructor");
        assertThat(src).contains("private final ProjectService projectService;");
        assertThat(src).doesNotContain("public ProjectController(");
        // BaseController.permPrefix() 自 0.2.0 起是 public——覆写不能收窄可见性，
        // 写成 protected 会编译不过（实测在 sample-app 上踩过）
        assertThat(src).contains("public String permPrefix()");
        assertThat(src).doesNotContain("protected String permPrefix()");
    }

    @Test
    @DisplayName("Javadoc 每行都以 * 开头，不会因拼接产生畸形注释")
    void javadocWellFormed() {
        String src = JavaGenerator.controller(spec);
        boolean inDoc = false;
        List<String> bad = new ArrayList<>();
        for (String line : src.split("\n")) {
            String t = line.strip();
            if (t.startsWith("/**")) {
                // 单行 Javadoc（/** ... */）是合法写法，不进入多行状态
                if (!t.endsWith("*/")) {
                    inDoc = true;
                }
                continue;
            }
            if (inDoc && t.startsWith("*/")) {
                inDoc = false;
                continue;
            }
            if (inDoc && !t.startsWith("*")) {
                bad.add(line);
            }
        }
        assertThat(bad).as("Javadoc 中出现了不以 * 开头的行").isEmpty();
    }

    // ------------------------------------------------------------------ DDL

    @Test
    @DisplayName("DDL 显式声明字符集与排序规则，且不使用被红线禁止的特性")
    void ddlRespectsRedLines() {
        String ddl = DdlGenerator.generate(spec);
        assertThat(ddl).contains("DEFAULT CHARACTER SET utf8mb4");
        assertThat(ddl).contains("COLLATE utf8mb4_general_ci");
        assertThat(ddl).contains("ENGINE=InnoDB");

        // 红线：时间用 DATETIME 不用 TIMESTAMP；无 CTE / 窗口函数 / 生成列 / CHECK
        assertThat(ddl).contains("DATETIME");
        assertThat(ddl).doesNotContain("TIMESTAMP");
        assertThat(ddl).doesNotContain("GENERATED ALWAYS");
        assertThat(ddl).doesNotContain("CHECK (");
        assertThat(ddl).doesNotContainIgnoringCase("WITH RECURSIVE");
        assertThat(ddl).doesNotContainIgnoringCase("OVER (");
    }

    @Test
    @DisplayName("DDL 含全部审计字段，且与 BaseEntity 一一对应")
    void ddlHasAuditColumns() {
        String ddl = DdlGenerator.generate(spec);
        assertThat(ddl)
                .contains("create_by").contains("create_time")
                .contains("update_by").contains("update_time")
                .contains("deleted").contains("version")
                .contains("PRIMARY KEY (id)");
    }

    @Test
    @DisplayName("indexed 字段生成索引，未标记的不生成")
    void ddlIndexes() {
        String ddl = DdlGenerator.generate(spec);
        assertThat(ddl).contains("KEY idx_biz_project_project_name (project_name)");
        assertThat(ddl).contains("KEY idx_biz_project_owner_dept_id (owner_dept_id)");
        assertThat(ddl).doesNotContain("idx_biz_project_budget");
    }

    @Test
    @DisplayName("凡是用到的类型都有对应 import —— 漏 import 是编译不过的硬错误")
    void generatedJavaHasAllImports() {
        for (String src : List.of(
                JavaGenerator.entity(spec),
                JavaGenerator.mapper(spec),
                JavaGenerator.service(spec),
                JavaGenerator.controller(spec))) {
            for (FieldType type : FieldType.values()) {
                String fqn = type.importName();
                if (fqn == null) {
                    continue;
                }
                String simple = type.javaSimpleType();
                // 只看代码体，注释里出现类型名不算使用
                String body = src.substring(src.indexOf("public "));
                if (body.contains(simple + " ") || body.contains("<" + simple + ">")) {
                    assertThat(src)
                            .as("用到了 %s 却没有 import %s", simple, fqn)
                            .contains("import " + fqn + ";");
                }
            }
        }
    }

    /**
     * 生成物必须自带 {@code /options}，否则业务方给表单加「选一个项目」这种下拉框时
     * 没有范式可抄，只能去调分页列表接口硬编码 {@code size=200}——那正是 CLAUDE.md 4.11
     * 要消掉的写法。
     */
    @Test
    @DisplayName("Controller 生成 /options 端点，复用本模块 list 权限点且不绕过数据权限")
    void controllerGeneratesOptionsEndpoint() {
        String src = JavaGenerator.controller(spec);

        assertThat(src).contains("@GetMapping(\"/options\")");
        assertThat(src).contains(
                "public Result<List<OptionItem>> options(@RequestParam(required = false) String keyword)");

        // 业务实体是「受控引用」档：复用本模块的 :list，而不是像组织元数据那样只要求登录
        assertThat(src).contains("requirePermission(\"list\")");

        // 必须走 Service —— 绕过它直接查 Mapper 会跳过 DeptDataPermissionHandler 的行级过滤，
        // 于是「本部门」档的角色能在下拉里看到全公司的数据，而列表页明明是过滤过的
        assertThat(src).contains("projectService.listOptionSource(wrapper, \"项目\")");

        // 显示名默认取第一个 string 字段：列名进 SQL、驼峰进 getter，搞反会在运行时才炸
        assertThat(src).contains(".select(\"id\", \"project_name\")");
        assertThat(src).contains("OptionItem.of(e.getId(), e.getProjectName())");

        assertThat(src).contains("import io.github.describeadmin.common.api.OptionItem;");
    }

    @Test
    @DisplayName("labelField 覆盖默认的「第一个 string 字段」")
    void optionsHonoursExplicitLabelField() {
        String src = JavaGenerator.controller(SpecLoaderTestAccess.parse(new org.yaml.snakeyaml.Yaml().load("""
                basePackage: com.example.demo
                module: project
                entity: Project
                table: biz_project
                comment: 项目
                labelField: projectCode
                fields:
                  - name: projectName
                    type: string
                    length: 128
                    comment: 项目名称
                  - name: projectCode
                    type: string
                    length: 64
                    comment: 项目编号
                """)));

        assertThat(src).contains(".select(\"id\", \"project_code\")");
        assertThat(src).contains("OptionItem.of(e.getId(), e.getProjectCode())");
        assertThat(src).doesNotContain("project_name");
    }

    /**
     * 一个 string 字段都没有时不生成 {@code /options}：下拉框里只显示一串雪花 id
     * 对使用者毫无意义，还会让人以为"选项功能坏了"。
     */
    @Test
    @DisplayName("实体没有可显示的名称时，不生成 /options")
    void skipsOptionsWhenNoLabelCandidate() {
        String src = JavaGenerator.controller(SpecLoaderTestAccess.parse(new org.yaml.snakeyaml.Yaml().load("""
                basePackage: com.example.demo
                module: reading
                entity: Reading
                table: biz_reading
                comment: 读数
                fields:
                  - name: deviceId
                    type: long
                    comment: 设备ID
                  - name: value
                    type: decimal
                    precision: 18
                    scale: 2
                    comment: 读数
                """)));

        assertThat(src).doesNotContain("/options");
        assertThat(src).doesNotContain("OptionItem");
    }

    @Test
    @DisplayName("Controller 覆写 buildListWrapper 并真正构造条件，而不是只在注释里承诺")
    void controllerActuallyFilters() {
        String src = JavaGenerator.controller(spec);

        // 必须覆写基类的钩子，而不是自己声明一个带 @GetMapping 的重载 ——
        // 后者与基类的 list 撞在同一个 GET 路径上，Spring 启动即报 Ambiguous mapping。
        // 断言的是「没有**裸** @GetMapping」（那种才会落在基类 list 的根路径上）；
        // 带子路径的 @GetMapping("/options") 是另一个端点，不冲突。
        assertThat(src).contains("protected Wrapper<ProjectEntity> buildListWrapper(");
        assertThat(src).doesNotContainPattern("@GetMapping[^(]");

        assertThat(src).contains("QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<>()");
        assertThat(src).contains("wrapper.likeRight(");
        assertThat(src).contains("wrapper.eq(");
        assertThat(src).contains("wrapper.ge(").contains("wrapper.le(");

        // 参数名用小驼峰（前端传的就是它），落到 SQL 的必须是列名，
        // 两者搞反会在运行时报 Unknown column。断言完整的一行，而不是各自出现过
        assertThat(src).contains(
                "wrapper.likeRight(text(params, \"projectName\") != null, \"project_name\", "
                        + "text(params, \"projectName\"));");
        assertThat(src).contains(
                "wrapper.ge(asDate(params, \"startDateStart\") != null, \"start_date\", "
                        + "asDate(params, \"startDateStart\"));");
    }

    @Test
    @DisplayName("取参与类型转换委托给 BaseController，生成物里不内联这些方法")
    void parsersAreDelegatedToBaseController() {
        String src = JavaGenerator.controller(spec);

        // 调用继承自 BaseController 的 text / asXxx —— 只调用不声明
        assertThat(src).contains("asDate(params, \"startDateStart\")");
        assertThat(src).contains("asInt(params, \"status\")");
        assertThat(src).contains("asLong(params, \"ownerDeptId\")");

        // 不再逐个 Controller 生成解析辅助方法（口径统一在框架侧演进）
        assertThat(src).doesNotContain("private static String text(");
        assertThat(src).doesNotContain("private static LocalDate asDate(");
        assertThat(src).doesNotContain("private static Integer asInt(");

        // 400 语义与异常类型现在都在 BaseController 里，生成物无需再 import
        assertThat(src).doesNotContain("ResultCode.BAD_REQUEST");
        assertThat(src).doesNotContain("import io.github.describeadmin.common.api.BizException;");
    }

    // ------------------------------------------------------------------ 包布局

    @Test
    @DisplayName("默认 nested：包名带模块层级")
    void nestedLayoutKeepsModuleSegment() {
        assertThat(JavaGenerator.entity(spec)).contains("package com.example.demo.project.entity;");
    }

    @Test
    @DisplayName("flat 布局：Java 包去掉模块层级，跨层 import 与 REST 路径不受影响")
    void flatLayoutDropsModuleSegment() throws Exception {
        ModuleSpec flat = SpecLoader.load(Path.of("examples/project.yaml"), "flat");

        assertThat(JavaGenerator.entity(flat)).contains("package com.example.demo.entity;");
        assertThat(JavaGenerator.mapper(flat))
                .contains("package com.example.demo.mapper;")
                .contains("import com.example.demo.entity.ProjectEntity;");

        String controller = JavaGenerator.controller(flat);
        assertThat(controller)
                .contains("package com.example.demo.controller;")
                .contains("import com.example.demo.service.ProjectService;")
                .contains("@RequestMapping(\"/api/project\")");
        // 不含模块段
        assertThat(controller).doesNotContain("com.example.demo.project.");
    }
}
