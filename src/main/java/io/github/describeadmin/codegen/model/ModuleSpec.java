package io.github.describeadmin.codegen.model;

import java.util.List;

/**
 * 模块定义 —— 生成器的唯一输入。
 *
 * <p>之所以以 YAML 描述而非读取数据库元数据（develop_plan.md 3.3.1）：
 * <ul>
 *   <li>各国产化数据库的 {@code information_schema} 结构、类型映射、注释字段都不一致，
 *       读元数据的路子在最需要生成器的场景下反而不可用</li>
 *   <li>政务项目中开发机通常连不上业主的库，"以数据库为输入"这个前提本身不成立</li>
 * </ul>
 *
 * <p>对 AI Agent 而言还有一个好处：写一份受 schema 约束的 YAML，
 * 比直接写四个 Java 文件的出错面小得多，且错误能在解析阶段被明确指出。
 *
 * @param basePackage 业务方基础包名，如 {@code com.example.demo}
 * @param module      模块名（小写），用于包路径与 URL，如 {@code project}
 * @param entity      实体名（大驼峰，不含 Entity 后缀），如 {@code Project}
 * @param table       表名，如 {@code biz_project}
 * @param comment     模块中文名，用于注释、DDL comment 与测试用例描述
 * @param apiPrefix   REST 路径前缀，默认 {@code /api/<module>}
 * @param layout      后端 Java 包布局（{@code nested} / {@code flat}），只影响 {@link #packageOf}
 * @param layoutOrigin 布局取值的来源，仅用于生成时的提示行（如「CODEGEN_LAYOUT 环境变量」）
 * @param labelField  被别的模块引用时用作显示名的字段名；{@code null} 表示按
 *                    {@link #optionLabelField()} 的规则自动挑一个
 * @param fields      业务字段；审计字段由 BaseEntity 承担，不在此声明
 */
public record ModuleSpec(
        String basePackage,
        String module,
        String entity,
        String table,
        String comment,
        String apiPrefix,
        Layout layout,
        String layoutOrigin,
        String labelField,
        List<FieldSpec> fields) {

    public ModuleSpec {
        // 防御性兜底：解析器总会给出非空布局，这里仅保证 packageOf 不会 NPE
        if (layout == null) {
            layout = Layout.NESTED;
            layoutOrigin = "默认";
        }
    }

    public String entityClass() {
        return entity + "Entity";
    }

    public String mapperClass() {
        return entity + "Mapper";
    }

    public String serviceClass() {
        return entity + "Service";
    }

    public String controllerClass() {
        return entity + "Controller";
    }

    public String packageOf(String layer) {
        return switch (layout) {
            case NESTED -> basePackage + "." + module + "." + layer;
            case FLAT -> basePackage + "." + layer;
        };
    }

    /** 实体名的小驼峰形式，用作变量名。 */
    public String entityVar() {
        return Character.toLowerCase(entity.charAt(0)) + entity.substring(1);
    }

    /** 参与列表查询的字段。 */
    public List<FieldSpec> queryFields() {
        return fields.stream().filter(f -> f.query() != FieldSpec.QueryMode.NONE).toList();
    }

    /**
     * 本模块被别的模块引用时，下拉框里显示哪个字段。
     *
     * <p>spec 里显式写了 {@code labelField} 就用它；否则取<b>第一个 STRING 字段</b>——
     * 实体的第一个文本字段几乎总是它的名称（{@code projectName}、{@code title}），
     * 这个猜测对就省事、错了就在 spec 里写一行覆盖，代价对称。
     *
     * <p>返回 {@code null} 表示这个实体<b>没有可显示的名称</b>（一个 STRING 字段都没有），
     * 此时不生成 {@code /options} 端点：生成一个只显示雪花 id 的下拉框毫无价值，
     * 反而会让人以为"选项功能坏了"。
     */
    public FieldSpec optionLabelField() {
        if (labelField != null) {
            return fields.stream().filter(f -> f.name().equals(labelField)).findFirst().orElse(null);
        }
        return fields.stream().filter(f -> f.type() == FieldType.STRING).findFirst().orElse(null);
    }
}
