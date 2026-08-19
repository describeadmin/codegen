package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.ModuleSpec;

/**
 * 生成菜单与权限点的 SQL。
 *
 * <p><b>为什么生成页面还不够</b>：前端用 {@code accessMode: 'backend'}，
 * 路由完全由 {@code sys_menu} 表下发。只生成 {@code .vue} 文件而没有菜单行，
 * 页面在系统里根本不可达——访问对应路径会落到 404，侧边栏也不会出现入口。
 * 这一条在本项目实测踩过（VERSION_BASELINE.md 发现 ⑪），所以由生成器一并产出。
 *
 * <p>按钮级权限点与页面里的按钮一一对应：{@code add} / {@code edit} / {@code remove}。
 * 前端用 {@code v-access:code} 控制显隐，因此「页面上有哪些按钮」与
 * 「谁能看到这些按钮」共用同一份数据。
 *
 * <p>SQL 遵守 MySQL 5.7 安全子集（CLAUDE.md 3.1）：只用
 * {@code INSERT ... SELECT ... WHERE NOT EXISTS} 保证幂等，
 * 不用 {@code INSERT IGNORE}（依赖唯一索引，而 sys_menu 因逻辑删除未建唯一索引），
 * 也不用 CTE 或窗口函数。
 */
public final class MenuSqlGenerator {

    /** 业务菜单默认挂在这个目录下；目录不存在时脚本会先建出来。 */
    private static final String ROOT_NAME = "业务管理";
    private static final String ROOT_PATH = "/biz";

    private MenuSqlGenerator() {
    }

    public static String generate(ModuleSpec s) {
        String route = s.apiPrefix().replaceFirst("^/api", "");
        String component = s.module() + "/index";
        String listPerm = s.module() + ":list";

        StringBuilder sb = new StringBuilder();
        sb.append(header(s));
        sb.append(rootDir());
        sb.append(menuRow(s, route, component, listPerm));
        for (String[] btn : new String[][]{
                {"新增", "add", "1"}, {"编辑", "edit", "2"}, {"删除", "remove", "3"}}) {
            sb.append(buttonRow(s, listPerm, btn[0], btn[1], btn[2]));
        }
        sb.append(grantToAdmin(s));
        return sb.toString();
    }

    private static String header(ModuleSpec s) {
        return """
                -- =============================================================================
                -- %s 菜单与权限点
                --
                -- 由 codegen 生成。前端 accessMode = backend，路由完全由 sys_menu 下发，
                -- 因此只生成 .vue 文件是不够的：没有菜单行，页面访问不到、侧边栏也没有入口。
                --
                -- 语法基线：MySQL 5.7 安全子集。幂等靠 INSERT ... SELECT ... WHERE NOT EXISTS，
                -- 不用 INSERT IGNORE（依赖唯一索引，而 sys_menu 因逻辑删除未建唯一索引）。
                --
                -- ⚠️ 执行前确认 component 列的取值与前端实际文件路径一致：
                --    '%s' 对应 frontend 的 src/views/%s/index.vue。
                --    写错了不会报错，只会在打开页面时静默退化成 404。
                -- =============================================================================

                """.formatted(s.comment(), s.module() + "/index", s.module());
    }

    private static String rootDir() {
        return """
                -- 业务菜单根目录（已存在则跳过）。component 必须是 BasicLayout，
                -- 前端会拿它去 layoutMap 查布局组件，留空会退化成 404 页
                INSERT INTO sys_menu (parent_id, menu_name, menu_type, perm_code, path, component,
                                      icon, sort, visible, create_time, update_time, deleted, version)
                SELECT 0, '%s', 'DIR', NULL, '%s', 'BasicLayout', 'lucide:layers', 10, 1,
                       NOW(), NOW(), 0, 0
                FROM DUAL
                WHERE NOT EXISTS (
                  SELECT 1 FROM sys_menu WHERE path = '%s' AND parent_id = 0 AND deleted = 0
                );

                """.formatted(ROOT_NAME, ROOT_PATH, ROOT_PATH);
    }

    private static String menuRow(ModuleSpec s, String route, String component, String listPerm) {
        return """
                -- 列表页
                INSERT INTO sys_menu (parent_id, menu_name, menu_type, perm_code, path, component,
                                      icon, sort, visible, create_time, update_time, deleted, version)
                SELECT m.id, '%s', 'MENU', '%s', '%s', '%s', 'lucide:table', 1, 1,
                       NOW(), NOW(), 0, 0
                FROM sys_menu m
                WHERE m.path = '%s' AND m.parent_id = 0 AND m.deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perm_code = '%s' AND deleted = 0);

                """.formatted(s.comment(), listPerm, route, component, ROOT_PATH, listPerm);
    }

    private static String buttonRow(ModuleSpec s, String parentPerm,
                                    String label, String action, String sort) {
        String perm = s.module() + ":" + action;
        return """
                INSERT INTO sys_menu (parent_id, menu_name, menu_type, perm_code, path, component,
                                      icon, sort, visible, create_time, update_time, deleted, version)
                SELECT m.id, '%s', 'BUTTON', '%s', NULL, NULL, NULL, %s, 1, NOW(), NOW(), 0, 0
                FROM sys_menu m
                WHERE m.perm_code = '%s' AND m.deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perm_code = '%s' AND deleted = 0);

                """.formatted(label, perm, sort, parentPerm, perm);
    }

    private static String grantToAdmin(ModuleSpec s) {
        return """
                -- 授予 ADMIN 角色。
                -- ⚠️ 只授 ADMIN：其余角色该看到什么由业主在「角色管理」里决定，
                --    生成器替业主决定权限分配是越界的。
                INSERT INTO sys_role_menu (role_id, menu_id)
                SELECT r.id, m.id
                FROM sys_role r, sys_menu m
                WHERE r.role_code = 'ADMIN' AND r.deleted = 0
                  AND m.deleted = 0
                  AND (m.perm_code LIKE '%s:%%' OR m.path = '%s')
                  AND NOT EXISTS (
                    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
                  );
                """.formatted(s.module(), ROOT_PATH);
    }
}
