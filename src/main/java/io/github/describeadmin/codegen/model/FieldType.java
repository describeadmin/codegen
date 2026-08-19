package io.github.describeadmin.codegen.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 字段类型：YAML 中的类型标识 → Java 类型 + MySQL 列类型。
 *
 * <p><b>这个枚举是 SQL 红线的固化点</b>（CLAUDE.md 3.1）。刻意不提供的类型：
 * <ul>
 *   <li>{@code TIMESTAMP} —— 有 2038 年上限，且自动更新语义在 5.7 与各国产化库上不一致，
 *       时间一律用 {@code DATETIME}</li>
 *   <li>{@code JSON} —— 5.7 虽支持，但 8.0+ 的 JSON 函数集差异大，国产化库支持度参差，
 *       需要存 JSON 的场景用 {@code TEXT} 由应用层序列化</li>
 *   <li>{@code BOOLEAN} —— MySQL 中实为 {@code TINYINT(1)} 的别名，
 *       各库对它的处理不一致，统一显式用 {@code TINYINT}</li>
 * </ul>
 *
 * <p>把这些约束写进类型系统，而不是写进文档让人记——生成器天然产不出违规 SQL。
 */
public enum FieldType {

    STRING("string", "String", "VARCHAR", true, false),
    TEXT("text", "String", "TEXT", false, false),
    INT("int", "Integer", "INT", false, false),
    LONG("long", "Long", "BIGINT", false, false),
    DECIMAL("decimal", "java.math.BigDecimal", "DECIMAL", false, true),
    /** 0/1 语义，对应 MySQL 的 TINYINT，不使用 BOOLEAN 别名。 */
    FLAG("flag", "Integer", "TINYINT", false, false),
    DATE("date", "java.time.LocalDate", "DATE", false, false),
    /** 一律 DATETIME，不使用 TIMESTAMP。 */
    DATETIME("datetime", "java.time.LocalDateTime", "DATETIME", false, false);

    private final String key;
    private final String javaType;
    private final String sqlType;
    private final boolean needsLength;
    private final boolean needsPrecision;

    FieldType(String key, String javaType, String sqlType, boolean needsLength, boolean needsPrecision) {
        this.key = key;
        this.javaType = javaType;
        this.sqlType = sqlType;
        this.needsLength = needsLength;
        this.needsPrecision = needsPrecision;
    }

    public static Optional<FieldType> of(String key) {
        return Arrays.stream(values()).filter(t -> t.key.equalsIgnoreCase(key)).findFirst();
    }

    public static List<String> allKeys() {
        return Arrays.stream(values()).map(FieldType::key).toList();
    }

    public String key() {
        return key;
    }

    /** 简单名，用于生成代码；全限定名需要 import 时由 {@link #importName()} 提供。 */
    public String javaSimpleType() {
        int i = javaType.lastIndexOf('.');
        return i < 0 ? javaType : javaType.substring(i + 1);
    }

    /** 需要 import 的全限定名；{@code java.lang} 下的类型返回 {@code null}。 */
    public String importName() {
        return javaType.contains(".") ? javaType : null;
    }

    public String sqlType() {
        return sqlType;
    }

    public boolean needsLength() {
        return needsLength;
    }

    public boolean needsPrecision() {
        return needsPrecision;
    }
}
