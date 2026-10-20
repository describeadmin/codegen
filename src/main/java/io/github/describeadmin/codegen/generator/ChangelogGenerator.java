package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.ModuleSpec;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把建表 DDL 与菜单 SQL 组装成一份 Liquibase formatted SQL changelog。
 *
 * <p>业务方工程里 {@code src/main/resources/db/changelog/app/} 下的文件由框架的迁移层
 * （framework-system-starter 的 {@code DescribeAdminLiquibase}）按文件名顺序自动执行，
 * <b>不需要在任何地方登记</b>。文件名 {@code <yyyyMMddHHmm>__<表名>.sql}：时间戳保证执行顺序，
 * 多人 / 多 worktree 并行生成也不会撞号。
 *
 * <p>两个 changeset 的规矩不一样，写进了生成物的文件头：
 * <ul>
 *   <li>{@code <table>-create}：执行过就不能再改（Liquibase 校验和不符会拒绝启动），
 *       之后加列 / 改列要新建 changelog 文件写 ALTER。</li>
 *   <li>{@code <table>-menu}：{@code runOnChange}，改了会整体重跑一次，
 *       所以 {@link MenuSqlGenerator} 的每条语句都是 {@code INSERT ... WHERE NOT EXISTS}。</li>
 * </ul>
 *
 * <p>文件首行的 {@code logicalFilePath} 不能省：changeset 的身份是「id + author + 文件路径」，
 * 不固定的话文件挪个目录，已执行的 changeset 会被当成新的再执行一遍。框架启动时会检查这一条。
 */
public final class ChangelogGenerator {

    /** 业务 changelog 目录，相对后端工程根。 */
    public static final String DIR = "src/main/resources/db/changelog/app";

    /** changeset 的 author。业务方的 changelog 统一用它，id 才是区分点。 */
    static final String AUTHOR = "app";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private ChangelogGenerator() {
    }

    /** 新文件的文件名。已有同表的 changelog 时由调用方沿用旧名，不调用本方法。 */
    public static String fileName(ModuleSpec s, LocalDateTime now) {
        return now.format(STAMP) + "__" + s.table() + ".sql";
    }

    /** 判断一个已存在的文件名是否就是本表的 changelog。 */
    public static boolean isChangelogOf(ModuleSpec s, String fileName) {
        return fileName.matches("\\d{12}__" + java.util.regex.Pattern.quote(s.table()) + "\\.sql");
    }

    public static String generate(ModuleSpec s, String fileName) {
        return """
                --liquibase formatted sql logicalFilePath:app/%1$s

                -- =============================================================================
                -- %2$s（%3$s）
                --
                -- 由 codegen 生成。启动时由 describeadmin 的迁移层按文件名顺序执行，不需要在任何地方登记。
                --
                -- ⚠️ %3$s-create 执行过就【不能再改】：加列 / 改列请在同一目录新建
                --    <yyyyMMddHHmm>__<描述>.sql 写 ALTER。改了这里，所有已建库的环境都会因校验和不符拒绝启动。
                --    %3$s-menu 是 runOnChange，可以改，改了会整体重跑一次，所以语句必须保持幂等。
                -- =============================================================================

                --changeset %4$s:%3$s-create
                %5$s
                --changeset %4$s:%3$s-menu runOnChange:true
                %6$s""".formatted(fileName, s.comment(), s.table(), AUTHOR,
                DdlGenerator.generate(s), MenuSqlGenerator.generate(s));
    }
}
