package io.github.describeadmin.codegen.model;

/**
 * 模块菜单挂在哪个一级目录下，对应 spec 的 {@code menu.parent}。
 *
 * <p>生成的菜单 SQL 会先幂等地确保这个目录存在（{@code WHERE NOT EXISTS}），再把模块挂上去。
 * 所以每个模块的菜单 SQL 都自给自足，不依赖「别的文件先建好目录」——那种隐式依赖
 * 一旦执行顺序不对，{@code INSERT ... SELECT} 找不到父行就是 0 行受影响，不报任何错。
 *
 * <p>多个模块挂同一目录时，目录的名称 / 图标 / 排序以<b>先执行</b>的那份为准，
 * 后面的只是确认它存在。
 *
 * @param path 目录的路由路径，一级路径，如 {@code /roster}
 * @param name 目录名称，如 {@code 花名册}
 * @param icon 图标，如 {@code lucide:layers}
 * @param sort 侧边栏排序
 */
public record MenuParent(String path, String name, String icon, int sort) {

    public static final String DEFAULT_ICON = "lucide:layers";
    public static final int DEFAULT_SORT = 10;

    /** 未配置 {@code menu.parent} 时的默认目录。 */
    public static final MenuParent DEFAULT = new MenuParent("/biz", "业务管理", DEFAULT_ICON, DEFAULT_SORT);
}
