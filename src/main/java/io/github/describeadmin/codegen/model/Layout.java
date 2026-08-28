package io.github.describeadmin.codegen.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 后端 Java 包布局。只影响生成的 Java 文件落在哪个包下，不影响其它产物。
 *
 * <ul>
 *   <li>{@link #NESTED}（默认）—— {@code <basePackage>.<module>.<layer>}，
 *       如 {@code com.example.demo.project.controller}。按模块隔离，适合多模块工程。</li>
 *   <li>{@link #FLAT} —— {@code <basePackage>.<layer>}，
 *       如 {@code com.example.demo.controller}。不分模块层级，适合只有少量模块的小工程。</li>
 * </ul>
 *
 * <p>前端目录、{@code schema-*.sql} / {@code menu-*.sql}、{@code test-specs/*.yaml}、
 * {@code permPrefix()} 与 {@code @RequestMapping} 一律以模块名 / 表名为准，
 * <b>不受本选项影响</b>。
 */
public enum Layout {

    NESTED,
    FLAT;

    /** 供命令行、spec、环境变量三处使用的小写标识。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 解析一个取值（大小写不敏感、允许首尾空白）；非法值返回空。 */
    public static Optional<Layout> of(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "nested" -> Optional.of(NESTED);
            case "flat" -> Optional.of(FLAT);
            default -> Optional.empty();
        };
    }
}
