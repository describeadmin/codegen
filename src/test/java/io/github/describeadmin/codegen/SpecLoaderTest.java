package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecException;
import io.github.describeadmin.codegen.parser.SpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Spec 解析与校验")
class SpecLoaderTest {

    private static ModuleSpec parse(String yaml) {
        return SpecLoaderTestAccess.parse(new Yaml().load(yaml));
    }

    private static final String MINIMAL = """
            basePackage: com.example.demo
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
            """;

    @Test
    @DisplayName("最小可用 spec 能解析")
    void minimal() {
        ModuleSpec s = parse(MINIMAL);
        assertThat(s.entityClass()).isEqualTo("ProjectEntity");
        assertThat(s.controllerClass()).isEqualTo("ProjectController");
        assertThat(s.apiPrefix()).isEqualTo("/api/project");
        assertThat(s.packageOf("entity")).isEqualTo("com.example.demo.project.entity");
        assertThat(s.fields()).hasSize(1);
        assertThat(s.fields().get(0).column()).as("column 缺省时由 name 推导").isEqualTo("project_name");
    }

    @Test
    @DisplayName("一次性报出全部错误，而非遇到第一个就中断")
    void reportsAllErrorsAtOnce() {
        String bad = """
                module: Project
                entity: projectEntity
                table: SysProject
                fields:
                  - name: Foo
                    type: nope
                """;
        assertThatThrownBy(() -> parse(bad))
                .isInstanceOf(SpecException.class)
                .satisfies(e -> {
                    var errs = ((SpecException) e).errors();
                    // 缺 basePackage、module 大写、entity 小写开头+Entity 后缀、table 大写、
                    // 字段名大写、类型未知 —— 应当一次全报出来
                    assertThat(errs).hasSizeGreaterThanOrEqualTo(5);
                });
    }

    @Test
    @DisplayName("与 BaseEntity 内置字段重名被拦下，并说明原因")
    void reservedFieldRejected() {
        String bad = MINIMAL + """
                  - name: createTime
                    type: datetime
                """;
        assertThatThrownBy(() -> parse(bad))
                .hasMessageContaining("与 BaseEntity 的内置字段重名")
                .hasMessageContaining("不要重复声明");
    }

    @Test
    @DisplayName("sys_ 前缀的表名被拦下")
    void sysPrefixRejected() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("biz_project", "sys_project")))
                .hasMessageContaining("sys_ 前缀")
                .hasMessageContaining("framework-system-starter");
    }

    // ------------------------------------------------------------------
    // 以下几条验证「SQL 红线固化在类型系统里」：生成器根本产不出违规 SQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("timestamp 类型不存在，且提示改用 datetime")
    void timestampRejectedWithHint() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("type: string", "type: timestamp")))
                .hasMessageContaining("未知类型")
                .hasMessageContaining("2038")
                .hasMessageContaining("请改用 datetime");
    }

    @Test
    @DisplayName("boolean 类型不存在，且提示改用 flag")
    void booleanRejectedWithHint() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("type: string", "type: boolean")))
                .hasMessageContaining("请改用 flag");
    }

    @Test
    @DisplayName("json 类型不存在，且说明国产化库支持度问题")
    void jsonRejectedWithHint() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("type: string", "type: json")))
                .hasMessageContaining("国产化库")
                .hasMessageContaining("请改用 text");
    }

    @Test
    @DisplayName("double 类型不存在，且提示金额用 decimal")
    void doubleRejectedWithHint() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("type: string", "type: double")))
                .hasMessageContaining("decimal");
    }

    @Test
    @DisplayName("超长 VARCHAR 建索引被拦下，给出 5.7 的键长上限与修法")
    void indexedVarcharTooLong() {
        assertThatThrownBy(() -> parse(MINIMAL.replace("length: 128", "length: 500")))
                .hasMessageContaining("767 字节")
                .hasMessageContaining("191");
    }

    @Test
    @DisplayName("TEXT 列建索引被拦下")
    void indexedTextRejected() {
        String bad = MINIMAL.replace("type: string\n    length: 128", "type: text");
        assertThatThrownBy(() -> parse(bad))
                .hasMessageContaining("TEXT 列不能直接建索引");
    }

    @Test
    @DisplayName("like 查询只允许用于字符串类型")
    void likeOnNonStringRejected() {
        String bad = """
                basePackage: com.example.demo
                module: project
                entity: Project
                table: biz_project
                fields:
                  - name: amount
                    type: decimal
                    query: like
                """;
        assertThatThrownBy(() -> parse(bad))
                .hasMessageContaining("query: like 仅适用于 string / text");
    }

    @Test
    @DisplayName("字段名与列名重复被拦下")
    void duplicateRejected() {
        String bad = MINIMAL + """
                  - name: projectName
                    type: string
                """;
        assertThatThrownBy(() -> parse(bad)).hasMessageContaining("重复");
    }

    @Test
    @DisplayName("类型映射正确：decimal → BigDecimal / DECIMAL(p,s)")
    void typeMapping() {
        String yaml = """
                basePackage: com.example.demo
                module: project
                entity: Project
                table: biz_project
                fields:
                  - name: budget
                    type: decimal
                    precision: 18
                    scale: 2
                    comment: 预算
                """;
        FieldSpec f = parse(yaml).fields().get(0);
        assertThat(f.type()).isEqualTo(FieldType.DECIMAL);
        assertThat(f.type().javaSimpleType()).isEqualTo("BigDecimal");
        assertThat(f.sqlColumnType()).isEqualTo("DECIMAL(18,2)");
    }

    @Test
    @DisplayName("示例 spec 可解析（examples/ 与代码保持同步）")
    void exampleIsValid() throws Exception {
        ModuleSpec s = SpecLoader.load(Path.of("examples/project.yaml"));
        assertThat(s.module()).isEqualTo("project");
        assertThat(s.fields()).hasSize(7);
    }
}
