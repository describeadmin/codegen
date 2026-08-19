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
 * @param fields      业务字段；审计字段由 BaseEntity 承担，不在此声明
 */
public record ModuleSpec(
        String basePackage,
        String module,
        String entity,
        String table,
        String comment,
        String apiPrefix,
        List<FieldSpec> fields) {

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
        return basePackage + "." + module + "." + layer;
    }

    /** 实体名的小驼峰形式，用作变量名。 */
    public String entityVar() {
        return Character.toLowerCase(entity.charAt(0)) + entity.substring(1);
    }

    /** 参与列表查询的字段。 */
    public List<FieldSpec> queryFields() {
        return fields.stream().filter(f -> f.query() != FieldSpec.QueryMode.NONE).toList();
    }
}
