package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.generator.ChangelogGenerator;
import io.github.describeadmin.codegen.model.MenuParent;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecException;
import io.github.describeadmin.codegen.parser.SpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库变更以 Liquibase changelog 产出，菜单挂到 {@code menu.parent} 指定的目录下。
 */
@DisplayName("changelog 与菜单父目录")
class ChangelogGeneratorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 9, 5);

    private static ModuleSpec parse(String extra) {
        return SpecLoaderTestAccess.parse(new Yaml().load("""
                basePackage: com.example.demo
                module: work_order
                entity: WorkOrder
                table: biz_work_order
                comment: 工单
                fields:
                  - name: title
                    type: string
                    comment: 标题
                """ + extra));
    }

    // ---------------------------------------------------------------- 文件

    @Test
    @DisplayName("首行声明 logicalFilePath，且与文件名一致：文件挪目录也不会被当成新 changeset")
    void headerDeclaresLogicalFilePath() {
        ModuleSpec s = parse("");
        String name = ChangelogGenerator.fileName(s, NOW);
        assertThat(name).isEqualTo("202610190905__biz_work_order.sql");
        String sql = ChangelogGenerator.generate(s, name);
        assertThat(sql.lines().findFirst().orElseThrow())
                .isEqualTo("--liquibase formatted sql logicalFilePath:app/202610190905__biz_work_order.sql");
    }

    @Test
    @DisplayName("建表与菜单是两个 changeset：建表执行后不可改，菜单 runOnChange")
    void twoChangesets() {
        String sql = ChangelogGenerator.generate(parse(""), "x.sql");
        assertThat(sql).contains("--changeset app:biz_work_order-create\n")
                .contains("--changeset app:biz_work_order-menu runOnChange:true\n");
        assertThat(sql.indexOf("CREATE TABLE IF NOT EXISTS biz_work_order"))
                .isGreaterThan(sql.indexOf("-create"))
                .isLessThan(sql.indexOf("-menu runOnChange"));
        assertThat(sql.indexOf("INSERT INTO sys_menu")).isGreaterThan(sql.indexOf("-menu runOnChange"));
    }

    @Test
    @DisplayName("重新生成沿用同表已有的文件名，否则会多出一份同 id 的 changeset")
    void planReusesExistingFileName(@TempDir Path out) throws Exception {
        ModuleSpec s = parse("");
        Path dir = out.resolve(ChangelogGenerator.DIR);

        Map<Path, String> first = CodegenCli.plan(s, out, out.resolve("web"), NOW);
        assertThat(first).containsKey(dir.resolve("202610190905__biz_work_order.sql"));

        Files.createDirectories(dir);
        Files.writeString(dir.resolve("202501010000__biz_work_order.sql"), "x");
        Files.writeString(dir.resolve("202501010000__biz_work_order_item.sql"), "x");
        Map<Path, String> again = CodegenCli.plan(s, out, out.resolve("web"), NOW.plusDays(1));
        assertThat(again).containsKey(dir.resolve("202501010000__biz_work_order.sql"))
                .doesNotContainKey(dir.resolve("202610200905__biz_work_order.sql"));
        assertThat(again.get(dir.resolve("202501010000__biz_work_order.sql")))
                .startsWith("--liquibase formatted sql logicalFilePath:app/202501010000__biz_work_order.sql");
    }

    // ---------------------------------------------------------------- 菜单父目录

    @Test
    @DisplayName("不写 menu.parent 时挂在「业务管理」/biz 下")
    void defaultParent() {
        ModuleSpec s = parse("");
        assertThat(s.menuParent()).isEqualTo(MenuParent.DEFAULT);
        String sql = ChangelogGenerator.generate(s, "x.sql");
        assertThat(sql).contains("SELECT 0, '业务管理', 'DIR', NULL, '/biz', 'BasicLayout', 'lucide:layers', 10, 1,");
    }

    @Test
    @DisplayName("menu.parent 指定目录：菜单 SQL 自己幂等地确保目录存在，再挂上去")
    void customParent() {
        ModuleSpec s = parse("""
                menu:
                  parent:
                    path: /roster
                    name: 花名册
                    icon: lucide:users
                    sort: 20
                """);
        String sql = ChangelogGenerator.generate(s, "x.sql");
        assertThat(sql).contains("SELECT 0, '花名册', 'DIR', NULL, '/roster', 'BasicLayout', 'lucide:users', 20, 1,")
                .contains("SELECT 1 FROM sys_menu WHERE path = '/roster' AND parent_id = 0 AND deleted = 0")
                .contains("WHERE m.path = '/roster' AND m.parent_id = 0 AND m.deleted = 0")
                .contains("(m.path = '/roster' AND m.parent_id = 0)")
                .doesNotContain("'/biz'");
    }

    @Test
    @DisplayName("授权的 LIKE 里模块名的下划线被转义，不会把别的模块的权限点授出去")
    void likeEscapesUnderscore() {
        assertThat(ChangelogGenerator.generate(parse(""), "x.sql"))
                .contains("m.perm_code LIKE 'work\\_order:%'");
    }

    @Test
    @DisplayName("menu.parent 写错的地方一次性报出，未知键不静默忽略")
    void invalidMenuRejected() {
        assertThatThrownBy(() -> parse("""
                menu:
                  parnet: {}
                """))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("menu 下有未知的键 \"parnet\"");

        assertThatThrownBy(() -> parse("""
                menu:
                  parent:
                    path: roster/sub
                    sort: abc
                    color: red
                """))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("menu.parent.path \"roster/sub\" 应是以 / 开头的一级路径")
                .hasMessageContaining("缺少必填项 menu.parent.name")
                .hasMessageContaining("menu.parent.sort \"abc\" 应为整数")
                .hasMessageContaining("menu.parent 下有未知的键 \"color\"");
    }

    @Test
    @DisplayName("examples/project.yaml 仍是合法 spec")
    void exampleStillValid() throws Exception {
        assertThat(SpecLoader.load(Path.of("examples/project.yaml")).menuParent()).isNotNull();
    }
}
