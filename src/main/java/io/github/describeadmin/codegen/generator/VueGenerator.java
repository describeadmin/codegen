package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成前端页面与接口封装。
 *
 * <p><b>为什么这部分不能缺</b>：生成器产出的测试 Spec 断言的是
 * {@code [data-testid="xxx-add-btn"]} 这类选择器。如果页面不生成，
 * 那份 Spec 按定义就是跑不起来的——「代码与验收用例一起生成」只兑现了一半。
 * 本类补的正是另一半。
 *
 * <p>产出结构照搬 framework-system-starter 四个系统管理页面的既有形态
 * （列表 + 表单弹窗 + 受控删除确认），那套形态已经端到端跑通过，
 * 不另起炉灶。
 *
 * <p><b>data-testid 命名必须与 {@link TestSpecGenerator} 完全一致</b>，
 * 两者各写一套就会出现「用例找不到元素」而看起来像页面坏了。
 * 命名规则集中在本类的 {@code testId*} 方法里，Spec 生成器复用同一套规则。
 */
public final class VueGenerator {

    private VueGenerator() {
    }

    // ------------------------------------------------------------------ 命名

    /** {@code <模块>-<动作>-btn} 等固定锚点。 */
    public static String testId(ModuleSpec s, String suffix) {
        return s.module() + "-" + suffix;
    }

    /** 字段输入框锚点：{@code <模块>-<字段kebab>-input}。 */
    public static String fieldTestId(ModuleSpec s, FieldSpec f) {
        return s.module() + "-" + kebab(f.name()) + "-input";
    }

    static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 接口封装

    /**
     * 前端请求路径。
     *
     * <p><b>必须去掉 {@code /api} 前缀</b>：requestClient 的 baseURL 已经是 {@code /api}
     * （来自 VITE_GLOB_API_URL），这里再带一次会拼成 {@code /api/api/project}，
     * 后端按静态资源处理并返回 500，报错信息是 "No static resource api/api/project"，
     * 与"接口写错了"完全不像。这一条是端到端实跑才发现的。
     */
    static String clientPath(ModuleSpec s) {
        return s.apiPrefix().replaceFirst("^/api", "");
    }

    public static String api(ModuleSpec s) {
        String path = clientPath(s);
        String tsType = s.fields().stream()
                .map(f -> "  " + f.name() + "?: " + tsType(f.type()) + ";")
                .sorted()
                .collect(Collectors.joining("\n"));

        String queryType = s.queryFields().isEmpty()
                ? ""
                : s.queryFields().stream()
                        .flatMap(f -> f.query() == FieldSpec.QueryMode.RANGE
                                ? java.util.stream.Stream.of(
                                        "  " + f.name() + "End?: " + tsType(f.type()) + ";",
                                        "  " + f.name() + "Start?: " + tsType(f.type()) + ";")
                                : java.util.stream.Stream.of(
                                        "  " + f.name() + "?: " + tsType(f.type()) + ";"))
                        .sorted()
                        .collect(Collectors.joining("\n"));

        return """
                /**
                 * %s 接口。由 codegen 生成。
                 *
                 * 后端统一返回 Result：{ code, message, data, traceId }，
                 * code === 0 为成功。解包由 requestClient 的响应拦截器完成，
                 * 这里拿到的已经是 data 本身。
                 */
                import { requestClient } from '#/api/request';

                /** 审计字段由后端 BaseEntity 承担，业务代码不要赋值。 */
                export interface %sAudit {
                  createBy?: null | number;
                  createTime?: null | string;
                  id?: number;
                  updateBy?: null | number;
                  updateTime?: null | string;
                  version?: null | number;
                }

                export interface %s extends %sAudit {
                %s
                }

                export interface %sQuery {
                  current?: number;
                  size?: number;
                %s
                }

                export interface %sPage {
                  current: number;
                  pages: number;
                  records: %s[];
                  size: number;
                  total: number;
                }

                export async function get%sListApi(params: %sQuery) {
                  return requestClient.get<%sPage>('%s', { params });
                }

                export async function create%sApi(data: %s) {
                  return requestClient.post<%s>('%s', data);
                }

                export async function update%sApi(id: number, data: %s) {
                  return requestClient.put<%s>(`%s/${id}`, data);
                }

                export async function delete%sApi(id: number) {
                  return requestClient.delete(`%s/${id}`);
                }
                """.formatted(
                s.comment(),
                s.entity(),
                s.entity(), s.entity(), tsType,
                s.entity(), queryType,
                s.entity(), s.entity(),
                s.entity(), s.entity(), s.entity(), path,
                s.entity(), s.entity(), s.entity(), path,
                s.entity(), s.entity(), s.entity(), path,
                s.entity(), path);
    }

    // ------------------------------------------------------------------ 页面

    public static String page(ModuleSpec s) {
        List<FieldSpec> formFields = s.fields();
        List<FieldSpec> queryFields = s.queryFields();

        String elImports = elementImports(s);

        // 同一份字段初值要出现在两处缩进层级（顶层 reactive 与函数内 Object.assign），
        // 复用同一个字符串会让其中一处缩进错位 —— 按缩进各生成一份
        String formModel = formModel(formFields, "  ");
        String formModelIndented = formModel(formFields, "    ");

        String rules = formFields.stream()
                .filter(f -> !f.nullable())
                .map(f -> "  " + f.name() + ": [{ required: true, message: '请填写"
                        + f.comment() + "', trigger: 'blur' }],")
                .collect(Collectors.joining("\n"));
        String searchModel = queryFields.stream()
                .flatMap(f -> f.query() == FieldSpec.QueryMode.RANGE
                        ? java.util.stream.Stream.of(
                                "  " + f.name() + "End: undefined as " + tsType(f.type()) + " | undefined,",
                                "  " + f.name() + "Start: undefined as " + tsType(f.type()) + " | undefined,")
                        : java.util.stream.Stream.of(
                                "  " + f.name() + ": undefined as " + tsType(f.type()) + " | undefined,"))
                .collect(Collectors.joining("\n"));

        String columns = formFields.stream()
                .filter(f -> f.type() != FieldType.TEXT)
                .map(f -> "      <ElTableColumn prop=\"" + f.name() + "\" label=\"" + f.comment()
                        + "\" min-width=\"140\" />")
                .collect(Collectors.joining("\n"));

        String searchBar = queryFields.isEmpty() ? "" : searchBar(s, queryFields);
        String formItems = formFields.stream()
                .map(f -> formItem(s, f))
                .collect(Collectors.joining("\n"));

        return """
                <script lang="ts" setup>
                import type { %s } from '#/api/%s';

                import { onMounted, reactive, ref } from 'vue';

                import { Page } from '@describeadmin/ui';

                %s

                import {
                  create%sApi,
                  delete%sApi,
                  get%sListApi,
                  update%sApi,
                } from '#/api/%s';
                import ConfirmDialog from '#/components/confirm-dialog.vue';

                defineOptions({ name: '%s' });

                const loading = ref(false);
                const rows = ref<%s[]>([]);
                const total = ref(0);
                const page = reactive({ current: 1, size: 10 });

                /** 查询条件。空值不提交，交给后端按「空则不筛选」处理。 */
                const search = reactive({
                %s
                });

                const formVisible = ref(false);
                const submitting = ref(false);
                const editingId = ref<null | number>(null);
                const formRef = ref();

                const form = reactive({
                %s
                });

                const rules = {
                %s
                };

                const confirmVisible = ref(false);
                const deletingId = ref<null | number>(null);

                async function load() {
                  loading.value = true;
                  try {
                    const result = await get%sListApi({ ...page, ...search });
                    rows.value = result.records;
                    total.value = result.total;
                  } finally {
                    loading.value = false;
                  }
                }

                function doSearch() {
                  page.current = 1;
                  return load();
                }

                function resetSearch() {
                  for (const key of Object.keys(search)) {
                    (search as Record<string, unknown>)[key] = undefined;
                  }
                  return doSearch();
                }

                function openCreate() {
                  editingId.value = null;
                  Object.assign(form, {
                %s
                  });
                  formVisible.value = true;
                }

                function openEdit(row: %s) {
                  editingId.value = row.id ?? null;
                  Object.assign(form, row);
                  formVisible.value = true;
                }

                async function submit() {
                  await formRef.value?.validate();
                  submitting.value = true;
                  try {
                    await (editingId.value === null
                      ? create%sApi({ ...form })
                      : update%sApi(editingId.value, { ...form }));
                    ElMessage.success(editingId.value === null ? '新增成功' : '保存成功');
                    formVisible.value = false;
                    await load();
                  } finally {
                    submitting.value = false;
                  }
                }

                function askDelete(row: %s) {
                  deletingId.value = row.id ?? null;
                  confirmVisible.value = true;
                }

                async function confirmDelete() {
                  if (deletingId.value === null) {
                    return;
                  }
                  submitting.value = true;
                  try {
                    await delete%sApi(deletingId.value);
                    ElMessage.success('删除成功');
                    confirmVisible.value = false;
                    await load();
                  } finally {
                    submitting.value = false;
                  }
                }

                onMounted(load);
                </script>

                <template>
                  <Page description="由 codegen 生成，可直接修改" title="%s">
                    <template #extra>
                      <ElButton
                        type="primary"
                        data-testid="%s"
                        @click="openCreate"
                      >
                        新增
                      </ElButton>
                    </template>
                %s
                    <ElTable
                      v-loading="loading"
                      :data="rows"
                      row-key="id"
                      data-testid="%s"
                    >
                %s
                      <ElTableColumn label="操作" width="160" fixed="right">
                        <template #default="{ row }">
                          <ElButton
                            link
                            type="primary"
                            data-testid="%s"
                            @click="openEdit(row)"
                          >
                            编辑
                          </ElButton>
                          <ElButton
                            link
                            type="danger"
                            data-testid="%s"
                            @click="askDelete(row)"
                          >
                            删除
                          </ElButton>
                        </template>
                      </ElTableColumn>
                    </ElTable>

                    <div class="mt-4 flex justify-end">
                      <ElPagination
                        v-model:current-page="page.current"
                        v-model:page-size="page.size"
                        :total="total"
                        :page-sizes="[10, 20, 50]"
                        layout="total, sizes, prev, pager, next"
                        data-testid="%s"
                        @current-change="load"
                        @size-change="load"
                      />
                    </div>

                    <ElDialog
                      v-model="formVisible"
                      :title="editingId === null ? '新增%s' : '编辑%s'"
                      width="560px"
                      append-to-body
                      data-testid="%s"
                    >
                      <ElForm ref="formRef" :model="form" :rules="rules" label-width="110px">
                %s
                      </ElForm>
                      <template #footer>
                        <ElButton data-testid="%s" @click="formVisible = false">
                          取消
                        </ElButton>
                        <ElButton
                          type="primary"
                          :loading="submitting"
                          data-testid="%s"
                          @click="submit"
                        >
                          确定
                        </ElButton>
                      </template>
                    </ElDialog>

                    <ConfirmDialog
                      v-model="confirmVisible"
                      testid="%s"
                      :loading="submitting"
                      @confirm="confirmDelete"
                    />
                  </Page>
                </template>
                """.formatted(
                s.entity(), s.module(),
                elImports,
                s.entity(), s.entity(), s.entity(), s.entity(), s.module(),
                s.entity(),
                s.entity(),
                searchModel.isEmpty() ? "  // spec 中没有 query 字段，无查询条件" : searchModel,
                formModel,
                rules.isEmpty() ? "  // spec 中所有字段均可空，无必填校验" : rules,
                s.entity(),
                formModelIndented,
                s.entity(),
                s.entity(), s.entity(),
                s.entity(),
                s.entity(),
                s.comment(),
                testId(s, "add-btn"),
                searchBar,
                testId(s, "table"),
                columns,
                testId(s, "edit-btn"),
                testId(s, "delete-btn"),
                testId(s, "pagination"),
                s.comment(), s.comment(),
                testId(s, "form-dialog"),
                formItems,
                testId(s, "cancel-btn"),
                testId(s, "submit-btn"),
                s.module());
    }

    // ------------------------------------------------------------------ 片段

    private static String formModel(List<FieldSpec> fields, String indent) {
        return fields.stream()
                .map(f -> indent + f.name() + ": " + initialValue(f) + ",")
                .collect(Collectors.joining("\n"));
    }

    private static String elementImports(ModuleSpec s) {
        var names = new java.util.TreeSet<String>(List.of(
                "ElButton", "ElDialog", "ElForm", "ElFormItem", "ElMessage",
                "ElPagination", "ElTable", "ElTableColumn"));
        for (FieldSpec f : s.fields()) {
            names.add(controlOf(f.type()));
        }
        if (!s.queryFields().isEmpty()) {
            names.add("ElInput");
        }
        return "import {\n"
                + names.stream().map(n -> "  " + n + ",").collect(Collectors.joining("\n"))
                + "\n} from 'element-plus';";
    }

    private static String searchBar(ModuleSpec s, List<FieldSpec> queryFields) {
        String inputs = queryFields.stream()
                .flatMap(f -> f.query() == FieldSpec.QueryMode.RANGE
                        ? java.util.stream.Stream.of(
                                searchInput(s, f, f.name() + "Start", f.comment() + "（起）"),
                                searchInput(s, f, f.name() + "End", f.comment() + "（止）"))
                        : java.util.stream.Stream.of(searchInput(s, f, f.name(), f.comment())))
                .collect(Collectors.joining("\n"));

        return """

                    <ElForm inline class="mb-2" data-testid="%s">
                %s
                      <ElFormItem>
                        <ElButton
                          type="primary"
                          data-testid="%s"
                          @click="doSearch"
                        >
                          查询
                        </ElButton>
                        <ElButton data-testid="%s" @click="resetSearch">
                          重置
                        </ElButton>
                      </ElFormItem>
                    </ElForm>
                """.formatted(
                testId(s, "search-form"), inputs,
                testId(s, "search-btn"), testId(s, "reset-btn"));
    }

    private static String searchInput(ModuleSpec s, FieldSpec f, String model, String label) {
        return """
                      <ElFormItem label="%s">
                        <ElInput
                          v-model="search.%s"
                          clearable
                          data-testid="%s"
                          placeholder="请输入"
                          @keyup.enter="doSearch"
                        />
                      </ElFormItem>
                """.formatted(label, model, s.module() + "-" + kebab(model) + "-search")
                .stripTrailing();
    }

    /**
     * 表单项。
     *
     * <p>控件按字段类型选，而不是一律用文本框——`flag` 用开关、日期用日期选择器，
     * 生成出来就是能用的形态，不需要人再去逐个替换。
     */
    private static String formItem(ModuleSpec s, FieldSpec f) {
        String tid = fieldTestId(s, f);
        String model = "form." + f.name();
        String control = switch (f.type()) {
            case STRING -> """
                              <ElInput
                                v-model="%s"
                                data-testid="%s"
                                placeholder="请输入%s"
                              />
                    """.formatted(model, tid, f.comment()).stripTrailing();
            case TEXT -> """
                              <ElInput
                                v-model="%s"
                                type="textarea"
                                :rows="3"
                                data-testid="%s"
                                placeholder="请输入%s"
                              />
                    """.formatted(model, tid, f.comment()).stripTrailing();
            case INT, LONG -> """
                              <ElInputNumber
                                v-model="%s"
                                :step="1"
                                data-testid="%s"
                              />
                    """.formatted(model, tid).stripTrailing();
            case DECIMAL -> """
                              <ElInputNumber
                                v-model="%s"
                                :precision="%d"
                                :step="1"
                                data-testid="%s"
                              />
                    """.formatted(model, f.scale() == null ? 2 : f.scale(), tid).stripTrailing();
            case FLAG -> """
                              <ElSwitch
                                v-model="%s"
                                :active-value="1"
                                :inactive-value="0"
                                data-testid="%s"
                              />
                    """.formatted(model, tid).stripTrailing();
            case DATE -> """
                              <ElDatePicker
                                v-model="%s"
                                type="date"
                                value-format="YYYY-MM-DD"
                                data-testid="%s"
                                placeholder="请选择%s"
                              />
                    """.formatted(model, tid, f.comment()).stripTrailing();
            case DATETIME -> """
                              <ElDatePicker
                                v-model="%s"
                                type="datetime"
                                value-format="YYYY-MM-DD HH:mm:ss"
                                data-testid="%s"
                                placeholder="请选择%s"
                              />
                    """.formatted(model, tid, f.comment()).stripTrailing();
        };

        return """
                        <ElFormItem label="%s" prop="%s">
                %s
                        </ElFormItem>
                """.formatted(f.comment(), f.name(), control).stripTrailing();
    }

    private static String controlOf(FieldType type) {
        return switch (type) {
            case STRING, TEXT -> "ElInput";
            case INT, LONG, DECIMAL -> "ElInputNumber";
            case FLAG -> "ElSwitch";
            case DATE, DATETIME -> "ElDatePicker";
        };
    }

    /** 表单初值。日期与文本给空串，数字给 undefined —— 0 是有意义的业务值，不能当"未填"。 */
    private static String initialValue(FieldSpec f) {
        return switch (f.type()) {
            case STRING, TEXT, DATE, DATETIME -> "''";
            case INT, LONG, DECIMAL -> "undefined as number | undefined";
            case FLAG -> "1";
        };
    }

    private static String tsType(FieldType type) {
        return switch (type) {
            case STRING, TEXT, DATE, DATETIME -> "string";
            case INT, LONG, DECIMAL, FLAG -> "number";
        };
    }
}
