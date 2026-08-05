# 基础、商品与扫码入库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可运行的 Java/Vue/SQLite 应用，并交付商品建档、分类先行扫码入库、移动平均成本和库存追溯闭环。

**Architecture:** 仓库包含 `backend` 和 `frontend` 两个应用。后端使用按业务模块组织的 Spring Boot 模块化单体，使用 `JdbcClient` 显式执行 SQL；库存模块是唯一库存写入口。前端使用 Vue 3 单页应用，API 类型集中维护，页面按业务模块拆分。

**Tech Stack:** Java 21、Spring Boot 4.1、Maven、Spring Web/Validation/JDBC、Flyway、Xerial SQLite JDBC 3.53.1.0、JUnit 5；Node.js 24 LTS、Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Vitest、Playwright。

## Global Constraints

- 单门店、单仓库、单用户、无登录和权限功能。
- 金额和成本使用 `BigDecimal` 与 SQLite `NUMERIC`；金额统一保留 2 位，平均成本内部保留 4 位。
- 数量为大于零的整数；库存不得为负数。
- 条码与 SKU 编码全局唯一；有历史记录的数据只停用、不物理删除。
- 已确认单据不可修改或删除；单据、库存和流水必须在同一事务提交。
- 所有时间由后端生成并存为 UTC ISO-8601 字符串，界面按本地时区展示。
- 规格依据：`docs/superpowers/specs/2026-08-05-sports-inventory-system-design.md`。

---

## File Map

- `backend/src/main/java/com/sportshop/shared/`：统一错误、时钟、编号和幂等请求。
- `backend/src/main/java/com/sportshop/catalog/`：分类、品牌、SPU、SKU 与快速建档。
- `backend/src/main/java/com/sportshop/inventory/`：库存余额、成本算法、库存流水和调整接口。
- `backend/src/main/java/com/sportshop/inbound/`：入库草稿校验与确认事务。
- `backend/src/main/resources/db/migration/`：只增不改的 SQLite 迁移脚本。
- `frontend/src/modules/`：与后端业务模块对应的页面、API 和状态。
- `frontend/e2e/`：跨前后端核心流程测试。

### Task 1: 可运行的前后端骨架

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/sportshop/SportShopApplication.java`
- Create: `backend/src/main/java/com/sportshop/shared/web/HealthController.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/sportshop/shared/web/HealthControllerTest.java`
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`
- Create: `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/router/index.ts`
- Create: `frontend/src/shared/api/http.ts`
- Create: `frontend/src/shared/layout/AppLayout.vue`
- Create: `frontend/src/shared/layout/AppLayout.test.ts`

**Interfaces:**
- Produces: `GET /api/health -> {"status":"UP"}`；Axios 实例 `http` 的 base URL 为 `/api`。

- [ ] **Step 1: 写后端失败测试**

```java
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {
  @Autowired MockMvc mvc;
  @Test void returnsUp() throws Exception {
    mvc.perform(get("/api/health")).andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("UP"));
  }
}
```

- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=HealthControllerTest`，确认因应用骨架不存在而失败。**
- [ ] **Step 3: 创建 Spring Boot 4.1 Maven 项目，加入 Web、Validation、JDBC、Flyway Core、`flyway-database-nc-sqlite:12.6.0`、`sqlite-jdbc:3.53.1.0` 和 Test 依赖；实现 `HealthController` 返回 `Map.of("status", "UP")`，将数据库 URL 配置为 `jdbc:sqlite:${SPORTSHOP_DATA_DIR:./data}/sportshop.db`。**
- [ ] **Step 4: 运行 `cd backend && mvn test -Dtest=HealthControllerTest`，预期 1 个测试通过。**
- [ ] **Step 5: 写前端失败测试，挂载 `AppLayout` 后断言侧栏含“首页、商品管理、进货入库、库存管理、零售收银、销售退货、统计报表、系统设置”。**
- [ ] **Step 6: 运行 `cd frontend && npm test -- AppLayout.test.ts`，确认组件不存在而失败；随后实现 Vite/Vue/TypeScript 骨架、路由和 Element Plus 布局，再运行同一命令确认通过。**
- [ ] **Step 7: 运行 `cd backend && mvn test` 与 `cd frontend && npm test -- --run`。**
- [ ] **Step 8: 提交 `git add backend frontend && git commit -m "chore: scaffold sport shop applications"`。**

### Task 2: SQLite 迁移与数据访问测试底座

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__catalog_inventory_inbound.sql`
- Create: `backend/src/main/java/com/sportshop/shared/db/SQLiteConfiguration.java`
- Create: `backend/src/test/java/com/sportshop/support/DatabaseTestSupport.java`
- Create: `backend/src/test/java/com/sportshop/shared/db/SchemaMigrationTest.java`

**Interfaces:**
- Produces tables: `category`, `brand`, `product_spu`, `product_sku`, `sku_spec`, `inventory_balance`, `inbound_order`, `inbound_line`, `stock_movement`, `idempotency_request`。

- [ ] **Step 1: 写 `SchemaMigrationTest`，查询 `sqlite_master` 并断言上述 10 张表存在，同时尝试插入重复 `barcode` 并断言违反唯一约束。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=SchemaMigrationTest`，确认迁移文件缺失导致失败。**
- [ ] **Step 3: 编写 V1 SQL：主键使用文本 UUID；布尔值用 `INTEGER CHECK(value IN (0,1))`；金额字段用 `NUMERIC NOT NULL CHECK(value >= 0)`；数量使用 `INTEGER`；外键开启；为 `product_sku(barcode)`、`product_sku(sku_code)` 和业务单号建唯一索引。**
- [ ] **Step 4: 在 `SQLiteConfiguration` 的连接初始化中执行 `PRAGMA foreign_keys=ON`、`PRAGMA journal_mode=WAL`、`PRAGMA busy_timeout=5000`；测试使用每个测试类独立的 `target/test-data/<class>.db`。**
- [ ] **Step 5: 运行 `cd backend && mvn test -Dtest=SchemaMigrationTest`，确认表和约束测试通过。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add catalog and inventory schema"`。**

### Task 3: 商品分类、品牌、SPU 与 SKU API

**Files:**
- Create: `backend/src/main/java/com/sportshop/catalog/CatalogModels.java`
- Create: `backend/src/main/java/com/sportshop/catalog/CatalogRepository.java`
- Create: `backend/src/main/java/com/sportshop/catalog/CatalogService.java`
- Create: `backend/src/main/java/com/sportshop/catalog/CatalogController.java`
- Create: `backend/src/test/java/com/sportshop/catalog/CatalogServiceTest.java`
- Create: `backend/src/test/java/com/sportshop/catalog/CatalogControllerTest.java`

**Interfaces:**
- Produces: `CategoryView createCategory(String name)`、`BrandView createBrand(String name)`、`SkuView quickCreate(QuickCreateSkuCommand command)`、`ProductView updateProduct(UpdateProductCommand command)`、`Optional<SkuView> findByBarcode(String barcode)`、`void setSkuEnabled(UUID skuId, boolean enabled)`。
- HTTP: `GET/POST/PATCH /api/categories`、`GET/POST/PATCH /api/brands`、`GET/POST/PATCH /api/catalog/products`、`POST /api/catalog/skus/quick-create`、`GET /api/catalog/skus/by-barcode/{barcode}`、`PATCH /api/catalog/skus/{id}`、`PATCH /api/catalog/skus/{id}/enabled`。

- [ ] **Step 1: 写服务失败测试：快速建档创建 SPU、SKU、零库存余额；复用已有 SPU 时只新增 SKU；重复条码、重复 SKU 编码和空名称分别失败；完整编辑可更新商品描述、图片 URL、售价、预警库存和规格；有库存流水的 SKU 停用后仍可查询。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=CatalogServiceTest`，确认类型与服务不存在。**
- [ ] **Step 3: 定义 `QuickCreateSkuCommand(UUID categoryId, UUID brandId, UUID existingSpuId, String productName, String skuCode, String barcode, Map<String,String> specs, BigDecimal retailPrice, Integer warningStock)` 和只读 View records；实现 Repository 与 Service。**
- [ ] **Step 4: 运行服务测试并修正到全部通过。**
- [ ] **Step 5: 写控制器测试，验证创建返回 201、编辑返回 200、按条码未找到返回 404、重复字段返回 409、校验错误返回 400；实现分页商品查询、完整编辑、统一 `ProblemDetail` 异常映射和控制器。**
- [ ] **Step 6: 运行 `cd backend && mvn test -Dtest=CatalogServiceTest,CatalogControllerTest`。**
- [ ] **Step 7: 提交 `git add backend && git commit -m "feat: add catalog management API"`。**

### Task 4: 库存余额、移动平均成本与流水

**Files:**
- Create: `backend/src/main/java/com/sportshop/inventory/InventoryModels.java`
- Create: `backend/src/main/java/com/sportshop/inventory/InventoryRepository.java`
- Create: `backend/src/main/java/com/sportshop/inventory/InventoryService.java`
- Create: `backend/src/main/java/com/sportshop/inventory/InventoryController.java`
- Create: `backend/src/test/java/com/sportshop/inventory/InventoryServiceTest.java`

**Interfaces:**
- Produces: `StockChangeResult receive(UUID skuId, int quantity, BigDecimal unitCost, MovementSource source)`、`StockChangeResult issue(UUID skuId, int quantity, BigDecimal unitCost, MovementSource source)`、`InventoryPage search(InventoryQuery query)`。
- `MovementSource(String type, String documentId, String documentNo, String occurredAt)` 唯一标识库存业务来源。

- [ ] **Step 1: 写失败测试：库存 10、成本 100.0000 时，以数量 5、进价 130.00 入库后库存为 15、平均成本为 110.0000；出库不改变平均成本；库存不足抛出 `InsufficientStockException`；每次变化生成包含前后数量的流水。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=InventoryServiceTest`，确认失败。**
- [ ] **Step 3: 实现 `receive` 公式并使用 `RoundingMode.HALF_UP` 保留 4 位；`issue` 使用条件 SQL `UPDATE ... WHERE quantity >= ? AND version = ?`，更新失败时区分库存不足与版本冲突；余额与流水写入处于调用方事务内。**
- [ ] **Step 4: 实现库存查询 API `GET /api/inventory` 和 `GET /api/inventory/{skuId}/movements`，支持分类、品牌、名称、SKU 编码、条码和低库存过滤。**
- [ ] **Step 5: 运行库存测试和全部后端测试。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add inventory ledger and costing"`。**

### Task 5: 幂等的扫码入库确认事务

**Files:**
- Create: `backend/src/main/java/com/sportshop/shared/idempotency/IdempotencyService.java`
- Create: `backend/src/main/java/com/sportshop/inbound/InboundModels.java`
- Create: `backend/src/main/java/com/sportshop/inbound/InboundRepository.java`
- Create: `backend/src/main/java/com/sportshop/inbound/InboundService.java`
- Create: `backend/src/main/java/com/sportshop/inbound/InboundController.java`
- Create: `backend/src/test/java/com/sportshop/inbound/InboundServiceTest.java`
- Create: `backend/src/test/java/com/sportshop/inbound/InboundControllerTest.java`

**Interfaces:**
- Produces: `InboundReceipt confirm(ConfirmInboundCommand command)`，其中 command 含 `requestId`、`occurredAt`、`remark` 和 `List<InboundLineInput(skuId, quantity, unitCost)>`。
- HTTP: `POST /api/inbounds`、`GET /api/inbounds`、`GET /api/inbounds/{id}`；相同 `requestId` 返回第一次创建的同一入库单。

- [ ] **Step 1: 写失败测试：多 SKU 入库生成一张单据和逐 SKU 流水；平均成本正确；任一无效明细导致整单回滚；相同 requestId 调用两次只生成一张单据。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=InboundServiceTest`，确认失败。**
- [ ] **Step 3: 用 `@Transactional` 实现确认流程：校验非空明细、数量和价格；生成 `IN-yyyyMMdd-000001`；保存入库单及明细；逐行调用 `InventoryService.receive`；记录 requestId 与结果 ID。**
- [ ] **Step 4: 写控制器测试验证 201、400、409 和重复请求 200，以及按日期和单号分页查询入库历史与详情；实现控制器并使用 `Idempotency-Key` 请求头映射 requestId。**
- [ ] **Step 5: 运行入库测试及全部后端测试。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add idempotent inbound workflow"`。**

### Task 6: 商品与分类先行扫码入库界面

**Files:**
- Create: `frontend/src/modules/catalog/api.ts`, `frontend/src/modules/catalog/types.ts`
- Create: `frontend/src/modules/catalog/views/CatalogView.vue`
- Create: `frontend/src/modules/catalog/components/QuickCreateSkuDialog.vue`
- Create: `frontend/src/modules/catalog/views/CatalogView.test.ts`
- Create: `frontend/src/modules/inbound/api.ts`, `frontend/src/modules/inbound/types.ts`
- Create: `frontend/src/modules/inbound/stores/inboundDraft.ts`
- Create: `frontend/src/modules/inbound/views/InboundView.vue`
- Create: `frontend/src/modules/inbound/views/InboundHistoryView.vue`
- Create: `frontend/src/modules/inbound/components/BarcodeInput.vue`
- Create: `frontend/src/modules/inbound/components/InboundDraftTable.vue`
- Create: `frontend/src/modules/inbound/views/InboundView.test.ts`

**Interfaces:**
- Consumes Task 3/5 APIs。
- Produces store actions: `selectCategory(id)`、`scan(barcode)`、`addLine(sku, quantity, unitCost)`、`confirm()`。

- [ ] **Step 1: 写组件失败测试：未选分类时扫描按钮禁用；已知同分类条码加入清单；未知条码打开快速建档且分类不可改；异类条码显示实际分类；切换分类保留清单；商品页可维护分类、品牌、图片、描述、规格、售价、预警值和启停状态；入库历史可按日期和单号查看详情。**
- [ ] **Step 2: 运行 `cd frontend && npm test -- InboundView.test.ts`，确认失败。**
- [ ] **Step 3: 实现 API 类型、Pinia 草稿状态、完整商品管理页、入库历史页和扫码页面；条码提交后立即清空并重新聚焦；相同 SKU 同价累加，不同进价弹出明确选择。**
- [ ] **Step 4: 实现快速建档成功后自动回填 SKU，并要求用户输入数量与进价；确认按钮生成 UUID requestId，提交期间禁用，成功后清空草稿并显示单号。**
- [ ] **Step 5: 运行 `cd frontend && npm test -- --run` 与 `npm run type-check`。**
- [ ] **Step 6: 提交 `git add frontend && git commit -m "feat: add scan-first inbound interface"`。**

### Task 7: 第一阶段端到端验收

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/inbound-flow.spec.ts`
- Create: `README.md`

**Interfaces:**
- Verifies: 分类创建 → 未知条码快速建档 → 入库 → 库存余额与流水查询。

- [ ] **Step 1: 写 Playwright 测试，使用固定条码 `6900000000012`，断言入库 10 件、进价 100 后库存页面显示数量 10、平均成本 100.0000，流水来源显示对应入库单号。**
- [ ] **Step 2: 运行 `cd frontend && npm run test:e2e`，确认因缺少启动配置或流程缺陷而失败。**
- [ ] **Step 3: 配置 Playwright 同时启动后端与前端；README 写明 Java 21、Node 24、首次启动、数据目录、测试和清空开发数据库命令。**
- [ ] **Step 4: 运行 `cd backend && mvn test`、`cd frontend && npm test -- --run`、`cd frontend && npm run type-check`、`cd frontend && npm run test:e2e`，全部通过。**
- [ ] **Step 5: 提交 `git add README.md frontend && git commit -m "test: cover catalog and inbound workflow"`。**

## 第一阶段完成定义

- 应用可启动且无需登录。
- 能先选分类、扫码、快速建档并确认入库。
- 库存、移动平均成本、入库单和库存流水一致。
- 重复提交不产生重复单据。
- 后端、前端单元测试、类型检查和入库 E2E 全部通过。
