# 体育商品两级分类与条码编号实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有单层商品分类升级为大类、小类两级目录，并使扫码入库能够按条码前两位自动识别大类。

**Architecture:** 保留 `category` 作为大类表并新增编号、确认状态，新增 `sub_category` 作为小类表，`product_spu` 只关联小类。目录服务集中负责分类链与条码前缀校验，入库、销售、库存和报表通过目录视图消费该规则；前端改为先扫码识别大类，再选择小类快速建档。

**Tech Stack:** Java 21、Spring Boot 3、JdbcClient、Flyway、SQLite、JUnit 5、Vue 3、TypeScript、Vite、Vitest、Playwright。

**Spec:** `docs/superpowers/specs/2026-08-29-two-level-catalog-design.md`

## Global Constraints

- 大类编号和小类编号均为管理员手动输入的两位数字。
- 大类编号全局唯一；小类编号和名称只在所属大类内唯一。
- `00` 只用于迁移生成的“待分类”小类。
- 条码只能包含数字、至少三位、全局唯一，前两位必须等于所属大类编号。
- 大类未确认、分类链停用或商品仍在“待分类”小类时，禁止新增入库和销售。
- 升级必须保留现有主键、库存和历史单据；迁移失败不得留下部分状态。
- 本计划不增加供应商、多门店、账号权限或自动编号功能。

---

### Task 1: 数据库升级与旧数据迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__two_level_catalog.sql`
- Modify: `backend/src/test/java/com/sportshop/shared/db/SchemaMigrationTest.java`
- Create: `backend/src/test/java/com/sportshop/catalog/TwoLevelCatalogSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Flyway 现有 V1-V4 表结构和 SQLite 原子迁移机制。
- Produces: `category(code, code_confirmed)`、`sub_category`、`product_spu.sub_category_id` 以及条码格式约束。

- [ ] **Step 1: 写迁移失败测试**

```java
@Test
void migratesExistingCategoryToPendingMajorAndMinorWithoutChangingProductId() {
    migrateToVersion("4");
    UUID productId = insertLegacyCatalog();
    migrate();
    assertThat(jdbc.queryForObject("select code_confirmed from category", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select code from sub_category", String.class)).isEqualTo("00");
    assertThat(jdbc.queryForObject("select id from product_spu", String.class)).isEqualTo(productId.toString());
}
```

- [ ] **Step 2: 运行迁移测试并确认因 V5 不存在而失败**

Run: `mvn -q -Dtest=TwoLevelCatalogSchemaMigrationTest test`

Expected: FAIL，缺少 `code_confirmed` 或 `sub_category`。

- [ ] **Step 3: 实现 V5 原子表重建迁移**

```sql
ALTER TABLE category ADD COLUMN code TEXT;
ALTER TABLE category ADD COLUMN code_confirmed INTEGER NOT NULL DEFAULT 0 CHECK (code_confirmed IN (0, 1));
CREATE TABLE sub_category (
  id TEXT PRIMARY KEY NOT NULL,
  category_id TEXT NOT NULL REFERENCES category(id),
  code TEXT NOT NULL CHECK(length(code) = 2 AND code NOT GLOB '*[^0-9]*'),
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0, 1)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX ux_sub_category_parent_code ON sub_category(category_id, code);
CREATE UNIQUE INDEX ux_sub_category_parent_name ON sub_category(category_id, name);
```

迁移 SQL 还必须稳定分配 `01` 至 `99` 的待确认大类编号、为每个大类插入确定性 UUID 的 `00 待分类`，重建 `product_spu` 将旧 `category_id` 映射为 `sub_category_id`，并重建 `product_sku` 添加数字与最小长度检查。

- [ ] **Step 4: 增加新安装与升级安装断言并运行测试**

Run: `mvn -q -Dtest=SchemaMigrationTest,TwoLevelCatalogSchemaMigrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交迁移**

```bash
git add backend/src/main/resources/db/migration/V5__two_level_catalog.sql backend/src/test/java/com/sportshop/shared/db/SchemaMigrationTest.java backend/src/test/java/com/sportshop/catalog/TwoLevelCatalogSchemaMigrationTest.java
git commit -m "feat: migrate catalog to two levels"
```

### Task 2: 目录领域模型、校验与 API

**Files:**
- Modify: `backend/src/main/java/com/sportshop/catalog/CatalogModels.java`
- Modify: `backend/src/main/java/com/sportshop/catalog/CatalogRepository.java`
- Modify: `backend/src/main/java/com/sportshop/catalog/CatalogService.java`
- Modify: `backend/src/main/java/com/sportshop/catalog/CatalogController.java`
- Modify: `backend/src/test/java/com/sportshop/catalog/CatalogServiceTest.java`
- Modify: `backend/src/test/java/com/sportshop/catalog/CatalogControllerTest.java`

**Interfaces:**
- Produces: `CategoryView(id, code, name, sortOrder, enabled, codeConfirmed)`、`SubCategoryView(id, categoryId, code, name, sortOrder, enabled)`、带分类上下文的 `SkuLookupView`。
- Produces: `GET/POST/PATCH /api/categories`、`GET/POST/PATCH /api/categories/{categoryId}/subcategories`、`GET /api/catalog/categories/by-prefix/{prefix}`。
- Produces: `CatalogService.requireSkuOperational(UUID)` 供入库和销售复用。

- [ ] **Step 1: 写编号、唯一性、锁定和前缀校验失败测试**

```java
@Test void rejectsMajorCodeThatIsNotTwoDigits() { assertThatThrownBy(() -> service.createCategory("1", "球类")); }
@Test void allowsSameMinorCodeUnderDifferentMajors() { /* 分别创建 01 篮球并断言成功 */ }
@Test void rejectsChangingConfirmedMajorCodeWhenSkuExists() { /* 建档后改号断言冲突 */ }
@Test void rejectsBarcodeWhosePrefixDiffersFromMajor() { /* 02 分类下创建 01... 条码 */ }
```

- [ ] **Step 2: 运行目录服务测试并确认新接口缺失导致失败**

Run: `mvn -q -Dtest=CatalogServiceTest test`

Expected: FAIL，缺少新的命令、视图或服务方法。

- [ ] **Step 3: 实现模型、仓储和服务最小闭环**

```java
public record CategoryView(UUID id, String code, String name, int sortOrder,
                           boolean enabled, boolean codeConfirmed) {}
public record SubCategoryView(UUID id, UUID categoryId, String code, String name,
                              int sortOrder, boolean enabled) {}
public record QuickCreateSkuCommand(UUID subCategoryId, UUID brandId, UUID existingSpuId,
                                    String productName, String skuCode, String barcode,
                                    Map<String,String> specs, BigDecimal retailPrice,
                                    Integer warningStock) {}
```

服务统一通过 `requireTwoDigitCode`、`requireNumericBarcode`、`requireActiveCatalog` 校验，商品读写只传 `subCategoryId`，仓储查询一次联结得到所属大类。

- [ ] **Step 4: 写并验证 API 契约测试**

```java
mockMvc.perform(get("/api/catalog/categories/by-prefix/01"))
  .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("01"));
mockMvc.perform(get("/api/catalog/categories/by-prefix/88"))
  .andExpect(status().isNotFound());
```

Run: `mvn -q -Dtest=CatalogServiceTest,CatalogControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交目录后端**

```bash
git add backend/src/main/java/com/sportshop/catalog backend/src/test/java/com/sportshop/catalog
git commit -m "feat: add major and minor catalog APIs"
```

### Task 3: 业务链校验与报表分级

**Files:**
- Modify: `backend/src/main/java/com/sportshop/inbound/InboundService.java`
- Modify: `backend/src/main/java/com/sportshop/sales/SalesService.java`
- Modify: `backend/src/main/java/com/sportshop/inventory/InventoryModels.java`
- Modify: `backend/src/main/java/com/sportshop/inventory/InventoryRepository.java`
- Modify: `backend/src/main/java/com/sportshop/report/ReportModels.java`
- Modify: `backend/src/main/java/com/sportshop/report/ReportRepository.java`
- Modify: `backend/src/main/java/com/sportshop/report/ReportService.java`
- Modify: `backend/src/main/java/com/sportshop/report/ReportController.java`
- Modify: `backend/src/test/java/com/sportshop/inbound/InboundServiceTest.java`
- Modify: `backend/src/test/java/com/sportshop/sales/SalesServiceTest.java`
- Modify: `backend/src/test/java/com/sportshop/report/ReportServiceTest.java`

**Interfaces:**
- Consumes: `CatalogService.requireSkuOperational(UUID)`。
- Produces: 库存项目带 `categoryCode/categoryName/subCategoryCode/subCategoryName`。
- Produces: `GET /api/reports/category-share` 默认大类汇总并支持 `categoryId` 下钻小类。

- [ ] **Step 1: 写禁止待确认分类交易和报表分组失败测试**

```java
@Test void rejectsInboundWhenMajorCodeIsPending() { /* 迁移占位商品提交入库返回冲突 */ }
@Test void rejectsSaleWhenMinorCategoryIsDisabled() { /* 停用小类后扫码销售失败 */ }
@Test void categoryShareAggregatesByMajorAndDrillsIntoMinor() { /* 两个小类汇总与下钻 */ }
```

- [ ] **Step 2: 运行入库、销售和报表测试并确认失败**

Run: `mvn -q -Dtest=InboundServiceTest,SalesServiceTest,ReportServiceTest test`

Expected: FAIL，现有服务未校验两级分类或仍按旧分类联结。

- [ ] **Step 3: 改造入库、销售、库存与报表查询**

```java
public record CategoryShare(UUID categoryId, String categoryCode, String categoryName,
                            UUID subCategoryId, String subCategoryCode,
                            String subCategoryName, BigDecimal netSalesAmount) {}
```

默认汇总时小类字段为 `null`；传入大类 ID 时按该大类的小类分组。所有新交易先调用目录服务检查 SKU、SPU、小类、大类启用状态、确认状态和条码前缀。

- [ ] **Step 4: 运行后端完整测试**

Run: `mvn -q test`

Expected: PASS。

- [ ] **Step 5: 提交业务适配**

```bash
git add backend/src/main backend/src/test
git commit -m "feat: enforce catalog chain in inventory flows"
```

### Task 4: 商品管理与扫码优先入库前端

**Files:**
- Modify: `frontend/src/modules/catalog/types.ts`
- Modify: `frontend/src/modules/catalog/api.ts`
- Modify: `frontend/src/modules/catalog/views/CatalogView.vue`
- Modify: `frontend/src/modules/catalog/views/CatalogView.test.ts`
- Modify: `frontend/src/modules/catalog/components/QuickCreateSkuDialog.vue`
- Modify: `frontend/src/modules/inbound/views/InboundView.vue`
- Modify: `frontend/src/modules/inbound/views/InboundView.test.ts`

**Interfaces:**
- Consumes: Task 2 大类、小类、前缀识别和快速建档 API。
- Produces: 大类/小类联动管理，扫码后自动识别大类，未知 SKU 选择小类后快速建档。

- [ ] **Step 1: 写前端失败测试**

```ts
it('scans before choosing a category and limits quick create to recognized major', async () => {
  await wrapper.get('[data-testid="barcode-input"]').setValue('0112345')
  await wrapper.get('[data-testid="barcode-input"]').trigger('keydown.enter')
  expect(findCategoryByPrefix).toHaveBeenCalledWith('01')
  expect(wrapper.get('[data-testid="quick-major-category"]').text()).toContain('球类')
  expect(wrapper.findAll('[data-testid="quick-sub-category-option"]')).toHaveLength(2)
})
```

- [ ] **Step 2: 运行测试并确认旧的“先选分类”流程导致失败**

Run: `npm test -- --run src/modules/catalog/views/CatalogView.test.ts src/modules/inbound/views/InboundView.test.ts`

Expected: FAIL，缺少前缀查询与小类选择控件。

- [ ] **Step 3: 实现类型、API 和页面流程**

```ts
export interface Category { id: string; code: string; name: string; sortOrder: number; enabled: boolean; codeConfirmed: boolean }
export interface SubCategory { id: string; categoryId: string; code: string; name: string; sortOrder: number; enabled: boolean }
export interface Product { id: string; name: string; subCategoryId: string; categoryId: string; brandId: string; /* ... */ }
```

移除入库页预选分类卡片；输入条码后先校验数字和最小长度，再并行查完整 SKU 与前缀大类。已有 SKU 直接进入数量/进价；未知 SKU 将识别结果和所属可用小类传给快速建档弹窗；未知前缀显示“请先到商品管理建立对应大类”。

- [ ] **Step 4: 运行目录和入库前端测试**

Run: `npm test -- --run src/modules/catalog/views/CatalogView.test.ts src/modules/inbound/views/InboundView.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交核心前端**

```bash
git add frontend/src/modules/catalog frontend/src/modules/inbound
git commit -m "feat: scan barcodes before selecting minor category"
```

### Task 5: 库存、报表展示、端到端与发布回归

**Files:**
- Modify: `frontend/src/modules/inventory/types.ts`
- Modify: `frontend/src/modules/inventory/views/InventoryView.vue`
- Modify: `frontend/src/modules/inventory/exportInventoryCsv.ts`
- Modify: `frontend/src/modules/inventory/exportInventoryCsv.test.ts`
- Modify: `frontend/src/modules/reports/types.ts`
- Modify: `frontend/src/modules/reports/views/ReportsView.vue`
- Modify: `frontend/src/modules/reports/views/ReportsView.test.ts`
- Modify: `frontend/e2e/inbound-flow.spec.ts`
- Modify: `frontend/e2e/full-business-flow.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 3 分类上下文和报表下钻响应。
- Produces: 库存/CSV 两级分类列、报表大类汇总和小类下钻、完整用户验收流程。

- [ ] **Step 1: 写 CSV 与报表下钻失败测试**

```ts
expect(csv).toContain('大类编号,大类名称,小类编号,小类名称')
await wrapper.get('[data-testid="category-share-drilldown"]').setValue(majorId)
expect(getCategoryShare).toHaveBeenLastCalledWith(range, majorId)
```

- [ ] **Step 2: 运行库存导出和报表页面测试并确认失败**

Run: `npm test -- --run src/modules/inventory src/modules/reports`

Expected: FAIL，旧类型与页面没有两级分类字段。

- [ ] **Step 3: 实现库存、CSV 和报表页面适配并更新说明**

页面以 `编号 + 名称` 展示大类和小类；报表初始请求不传大类 ID，选择大类后重新请求小类明细；README 记录编号和扫码规则。

- [ ] **Step 4: 运行完整验证**

Run: `mvn -q test`

Run: `npm test -- --run`

Run: `npm run build`

Run: `npm run test:e2e`

Expected: 所有命令退出码为 0，构建无 TypeScript 错误，端到端覆盖未知前缀、未知 SKU 快速建档、再次扫码直接入库和报表下钻。

- [ ] **Step 5: 提交最终适配**

```bash
git add frontend README.md
git commit -m "feat: expose two-level catalog across the app"
```
