package io.github.describeadmin.codegen.model;

/**
 * 字段定义。
 *
 * @param name       Java 字段名（小驼峰）
 * @param column     数据库列名（下划线）
 * @param type       字段类型
 * @param length     VARCHAR 长度；仅 {@link FieldType#STRING} 有意义
 * @param precision  DECIMAL 总位数
 * @param scale      DECIMAL 小数位
 * @param nullable   是否可空
 * @param comment    列注释，同时用于前端表单标签
 * @param query      列表查询方式
 * @param indexed    是否建索引
 */
public record FieldSpec(
        String name,
        String column,
        FieldType type,
        Integer length,
        Integer precision,
        Integer scale,
        boolean nullable,
        String comment,
        QueryMode query,
        boolean indexed) {

    /** 列表查询方式。 */
    public enum QueryMode {
        /** 不参与查询。 */
        NONE,
        /** 等值匹配。 */
        EQ,
        /** 右模糊（{@code LIKE 'x%'}），可走索引；不生成左模糊以免全表扫描。 */
        LIKE,
        /** 范围查询，生成 start/end 两个参数。 */
        RANGE
    }

    /** DDL 中的完整列类型，如 {@code VARCHAR(128)}、{@code DECIMAL(18,2)}。 */
    public String sqlColumnType() {
        if (type.needsLength()) {
            return type.sqlType() + "(" + (length == null ? 255 : length) + ")";
        }
        if (type.needsPrecision()) {
            return type.sqlType() + "(" + (precision == null ? 18 : precision)
                    + "," + (scale == null ? 2 : scale) + ")";
        }
        return type.sqlType();
    }
}
