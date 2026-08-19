package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.generator.MenuSqlGenerator;
import io.github.describeadmin.codegen.generator.TestSpecGenerator;
import io.github.describeadmin.codegen.generator.VueGenerator;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("前端生成")
class FrontendGeneratorTest {

    /** 同时匹配页面里的 data-testid="x" 与 Spec 里的 [data-testid="x"]。 */
    private static final Pattern TEST_ID = Pattern.compile("data-testid=\"([^\"]+)\"");

    private static ModuleSpec spec;

    @BeforeAll
    static void load() throws Exception {
        spec = SpecLoader.load(Path.of("examples/project.yaml"));
    }

    private static Set<String> testIdsIn(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = TEST_ID.matcher(text);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    // ------------------------------------------------------------- 防漂移

    @Test
    @DisplayName("测试 Spec 引用的每个 data-testid，页面里都真实存在")
    void specSelectorsExistInPage() {
        Set<String> pageIds = testIdsIn(VueGenerator.page(spec));
        // ConfirmDialog 内部按 testid 前缀拼出确认/取消按钮，页面里看不到字面量
        pageIds.add(spec.module() + "-confirm-btn");
        pageIds.add(spec.module() + "-cancel-btn");

        Set<String> specIds = testIdsIn(TestSpecGenerator.generate(spec));

        assertThat(specIds).isNotEmpty();
        assertThat(specIds)
                .as("用例引用了页面里不存在的锚点，跑起来会定位失败，而现象看起来像页面坏了")
                .isSubsetOf(pageIds);
    }

    @Test
    @DisplayName("锚点命名遵循 <模块>-<对象>-<动作>")
    void testIdNaming() {
        Set<String> ids = testIdsIn(VueGenerator.page(spec));
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith(spec.module() + "-"));
        assertThat(ids).contains(
                "project-add-btn", "project-submit-btn", "project-table",
                "project-edit-btn", "project-delete-btn",
                "project-project-name-input", "project-budget-input");
    }

    // ------------------------------------------------------------- 页面

    @Test
    @DisplayName("控件按字段类型选，不是一律文本框")
    void controlsMatchFieldTypes() {
        String page = VueGenerator.page(spec);
        // status 是 flag → 开关；startDate 是 date → 日期选择器；budget 是 decimal → 数字框
        assertThat(page).contains("<ElSwitch").contains(":active-value=\"1\"");
        assertThat(page).contains("<ElDatePicker").contains("value-format=\"YYYY-MM-DD\"");
        assertThat(page).contains("<ElInputNumber").contains(":precision=\"2\"");
        // remark 是 text → 文本域
        assertThat(page).contains("type=\"textarea\"");
    }

    @Test
    @DisplayName("TEXT 字段不进列表列 —— 长文本会把表格挤垮")
    void textFieldNotInTable() {
        String page = VueGenerator.page(spec);
        assertThat(page).doesNotContain("<ElTableColumn prop=\"remark\"");
        assertThat(page).contains("<ElTableColumn prop=\"projectName\"");
    }

    @Test
    @DisplayName("有 query 字段就生成搜索栏，且带重置")
    void searchBarGenerated() {
        String page = VueGenerator.page(spec);
        assertThat(page).contains("data-testid=\"project-search-btn\"");
        assertThat(page).contains("data-testid=\"project-reset-btn\"");
        // range 字段生成起止两个输入
        assertThat(page).contains("search.startDateStart").contains("search.startDateEnd");
    }

    @Test
    @DisplayName("只 import 用得到的 Element Plus 组件")
    void importsAreMinimal() {
        String page = VueGenerator.page(spec);
        int end = page.indexOf("} from 'element-plus';");
        assertThat(end).as("应存在 element-plus 的 import 块").isGreaterThan(0);
        String importBlock = page.substring(page.lastIndexOf("import {", end), end);

        assertThat(importBlock).contains("ElSwitch").contains("ElDatePicker");
        // spec 里没有需要下拉的字段
        assertThat(importBlock).doesNotContain("ElSelect");
    }

    // ------------------------------------------------------------- 接口封装

    @Test
    @DisplayName("请求路径不能带 /api 前缀 —— requestClient 的 baseURL 已经是 /api")
    void apiPathHasNoDuplicatePrefix() {
        String api = VueGenerator.api(spec);
        // 带上就会拼成 /api/api/project，后端按静态资源处理返回 500，
        // 报错是 "No static resource api/api/project"，与「接口写错了」完全不像
        assertThat(api).doesNotContain("'/api/");
        assertThat(api).doesNotContain("`/api/");
        assertThat(api).contains("requestClient.get<ProjectPage>('/project'");
        assertThat(api).contains("requestClient.post<Project>('/project'");
        assertThat(api).contains("/project/${id}");
    }

    @Test
    @DisplayName("类型映射正确")
    void apiClient() {
        String api = VueGenerator.api(spec);
        // decimal / long → number，date → string
        assertThat(api).contains("budget?: number;").contains("startDate?: string;");
        // range 字段在查询类型里拆成起止两个
        assertThat(api).contains("startDateStart?: string;").contains("startDateEnd?: string;");
    }

    // ------------------------------------------------------------- 菜单 SQL

    /**
     * 去掉 {@code --} 行注释后的 SQL。
     *
     * <p>红线断言必须只看**可执行语句**：生成物的注释里会写明"不用 INSERT IGNORE"
     * 这类说明，直接对全文做 doesNotContain，等于因为"文档说明了这条禁令"而判它违规。
     */
    private static String executableSql(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    @DisplayName("菜单 SQL 遵守 5.7 安全子集且幂等")
    void menuSqlRespectsRedLines() {
        String sql = executableSql(MenuSqlGenerator.generate(spec));
        assertThat(sql).contains("NOT EXISTS");
        assertThat(sql).doesNotContain("INSERT IGNORE");
        assertThat(sql).doesNotContain("ON DUPLICATE KEY");
        assertThat(sql).doesNotContainIgnoringCase("WITH RECURSIVE");
        assertThat(sql).doesNotContainIgnoringCase("ROW_NUMBER");
        assertThat(sql).doesNotContainIgnoringCase("OVER (");
    }

    @Test
    @DisplayName("菜单 component 与前端实际生成的文件路径对得上")
    void menuComponentMatchesGeneratedFile() {
        String sql = MenuSqlGenerator.generate(spec);
        // 写错了不会报错，只会在打开页面时静默退化成 404，所以必须锁死
        assertThat(sql).contains("'project/index'");
        assertThat(sql).contains("'/project'");
        // 顶层目录必须是 BasicLayout，否则前端查不到布局组件
        assertThat(sql).contains("'BasicLayout'");
    }

    @Test
    @DisplayName("按钮权限点与页面按钮一一对应")
    void buttonPermissionsMatchPage() {
        String sql = MenuSqlGenerator.generate(spec);
        assertThat(sql).contains("'project:add'")
                .contains("'project:edit'")
                .contains("'project:remove'")
                .contains("'project:list'");
    }
}
