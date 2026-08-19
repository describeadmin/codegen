package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.generator.DdlGenerator;
import io.github.describeadmin.codegen.generator.JavaGenerator;
import io.github.describeadmin.codegen.generator.TestSpecGenerator;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    @DisplayName("按需生成 import，不生成 java.lang 下的类型")
    void entityImports() {
        String src = JavaGenerator.entity(spec);
        assertThat(src).contains("import java.math.BigDecimal;");
        assertThat(src).contains("import java.time.LocalDate;");
        assertThat(src).doesNotContain("import String;");
        assertThat(src).doesNotContain("import java.lang.");
    }

    @Test
    @DisplayName("Controller 继承基类并绑定正确的泛型与路径")
    void controller() {
        String src = JavaGenerator.controller(spec);
        assertThat(src).contains(
                "extends BaseController<ProjectService, ProjectMapper, ProjectEntity>");
        assertThat(src).contains("@RequestMapping(\"/api/project\")");
        assertThat(src).contains("protected ProjectService getService()");
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

    // ------------------------------------------------------------------ 测试 Spec

    @Test
    @DisplayName("生成的测试 Spec 必须是合法 YAML —— 缩进与引号错误会在此暴露")
    void testSpecIsValidYaml() {
        String yaml = TestSpecGenerator.generate(spec);
        // 多文档：用 loadAll。这条断言专门防住"看起来没问题但结构错乱"的产出
        List<Object> docs = new ArrayList<>();
        new Yaml().loadAll(yaml).forEach(docs::add);
        assertThat(docs).as("应生成两个场景").hasSize(2);

        for (Object doc : docs) {
            assertThat(doc).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            assertThat(m).containsKeys("scenario", "steps", "assertions", "evidence");
            assertThat((List<?>) m.get("steps")).as("步骤应被正确解析为列表").isNotEmpty();
        }
    }

    @Test
    @DisplayName("每个步骤都是结构完整的对象（缩进错会退化成字符串）")
    void testSpecStepsAreObjects() {
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) new Yaml()
                .loadAll(TestSpecGenerator.generate(spec)).iterator().next();

        List<?> steps = (List<?>) first.get("steps");
        assertThat(steps).allSatisfy(step -> assertThat(step).isInstanceOf(Map.class));

        long fillCount = steps.stream()
                .map(o -> (Map<?, ?>) o)
                .filter(mm -> "fill".equals(mm.get("action")))
                .count();
        assertThat(fillCount).as("应为前几个非 TEXT 字段生成填充步骤").isEqualTo(3);
    }

    @Test
    @DisplayName("断言同时覆盖 UI 与 DB，且 DB 断言比对具体值")
    void testSpecHasBothUiAndDbAssertions() {
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) new Yaml()
                .loadAll(TestSpecGenerator.generate(spec)).iterator().next();

        List<?> assertions = (List<?>) first.get("assertions");
        List<String> types = assertions.stream()
                .map(o -> String.valueOf(((Map<?, ?>) o).get("type"))).toList();
        assertThat(types).contains("ui", "db");

        String query = assertions.stream()
                .map(o -> (Map<?, ?>) o)
                .filter(mm -> "db".equals(mm.get("type")))
                .map(mm -> String.valueOf(mm.get("query")))
                .findFirst().orElseThrow();
        // SQL 字符串字面量必须是单引号，双引号会同时破坏 YAML 与 SQL
        assertThat(query).contains("'测试项目名称-自动化'");
        assertThat(query).doesNotContain("\"");
    }

    @Test
    @DisplayName("data-testid 命名遵循 <模块>-<对象>-<动作>")
    void testSpecUsesTestIds() {
        String yaml = TestSpecGenerator.generate(spec);
        assertThat(yaml)
                .contains("data-testid=\"project-add-btn\"")
                .contains("data-testid=\"project-submit-btn\"")
                .contains("data-testid=\"project-table\"")
                .contains("data-testid=\"project-project-name-input\"");
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

    @Test
    @DisplayName("Controller 覆写 buildListWrapper 并真正构造条件，而不是只在注释里承诺")
    void controllerActuallyFilters() {
        String src = JavaGenerator.controller(spec);

        // 必须覆写基类的钩子，而不是自己声明一个带 @GetMapping 的重载 ——
        // 后者与基类的 list 撞在同一个 GET 路径上，Spring 启动即报 Ambiguous mapping
        assertThat(src).contains("protected Wrapper<ProjectEntity> buildListWrapper(");
        assertThat(src).doesNotContain("@GetMapping");

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
    @DisplayName("参数转换失败返回 400 而不是 500")
    void malformedQueryParamIsClientError() {
        String src = JavaGenerator.controller(spec);
        // 「日期填错了」是使用者的输入问题，报 500 会把排查方向带偏
        assertThat(src).contains("ResultCode.BAD_REQUEST");
        assertThat(src).contains("参数格式不正确");
    }

    @Test
    @DisplayName("同一种类型的转换方法只生成一份")
    void parsersAreDeduplicated() {
        String src = JavaGenerator.controller(spec);
        // startDate 是 range，会用两次 asDate，但方法只能有一个
        int declarations = src.split(java.util.regex.Pattern.quote(
                "private static LocalDate asDate("), -1).length - 1;
        assertThat(declarations).as("asDate 应只定义一次").isEqualTo(1);
    }
}
