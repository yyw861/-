# 报表、设置、备份与发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在完整进销退闭环上交付首页、统计报表、库存盘点、门店设置、SQLite 安全备份恢复和可交付的单机发行包。

**Architecture:** 报表模块只读业务表并统一按销售减退货口径聚合；设置模块维护单店配置与字典；备份模块通过 SQLite 一致性快照生成备份文件，恢复操作先保护当前数据再替换数据库。发布时由 Spring Boot 托管前端静态资源，保持单进程、单数据库文件部署。

**Tech Stack:** 延续前两阶段技术栈；增加 ECharts 用于图表，使用 SQLite `VACUUM INTO` 或 JDBC 备份 API 生成一致性快照。

**Spec:** `docs/superpowers/specs/2026-08-05-sports-inventory-system-design.md`

## Global Constraints

- 依赖前两个实施计划全部完成。
- 报表金额口径为已确认销售减已确认退货；毛利使用销售成本快照与退货成本快照。
- 库存金额为当前数量乘当前移动平均成本。
- 不增加登录、供应商、会员、多门店或员工权限。
- 恢复备份前必须再次确认并先自动备份当前数据库。
- 发布包在 Windows 上无需单独安装数据库服务。

---

### Task 1: 系统设置、操作日志与库存调整结构

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__settings_adjustments_audit.sql`
- Create: `backend/src/test/java/com/sportshop/settings/SettingsSchemaMigrationTest.java`

**Interfaces:**
- Produces tables: `store_setting`、`document_sequence`、`receipt_setting`、`operation_log`、`stock_adjustment_order`、`stock_adjustment_line`、`backup_record`。

- [ ] **Step 1: 写迁移失败测试，断言表存在、门店设置只有固定键 `default`、调整数量不得为零、备份记录状态限定为 STARTED/SUCCEEDED/FAILED。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=SettingsSchemaMigrationTest`，确认失败。**
- [ ] **Step 3: 编写 V4 SQL（V3 已由销售退货模块使用），插入默认门店、默认小票设置和单号序列；为操作日志的发生时间与对象建立索引。**
- [ ] **Step 4: 运行迁移测试及全部后端测试。**
- [ ] **Step 5: 提交 `git add backend && git commit -m "feat: add settings and adjustment schema"`。**

### Task 2: 库存盘点调整

**Files:**
- Create: `backend/src/main/java/com/sportshop/inventory/adjustment/AdjustmentModels.java`
- Create: `backend/src/main/java/com/sportshop/inventory/adjustment/AdjustmentRepository.java`
- Create: `backend/src/main/java/com/sportshop/inventory/adjustment/AdjustmentService.java`
- Create: `backend/src/main/java/com/sportshop/inventory/adjustment/AdjustmentController.java`
- Create: `backend/src/test/java/com/sportshop/inventory/adjustment/AdjustmentServiceTest.java`
- Modify: `frontend/src/modules/inventory/views/InventoryView.vue`
- Create: `frontend/src/modules/inventory/components/AdjustmentDialog.vue`
- Create: `frontend/src/modules/inventory/components/AdjustmentDialog.test.ts`
- Create: `frontend/src/modules/inventory/exportInventoryCsv.ts`

**Interfaces:**
- Produces: `AdjustmentReceipt adjust(AdjustStockCommand command)`，每行包含 skuId、系统数量、盘点数量和非空原因。
- HTTP: `POST /api/inventory/adjustments`、`GET /api/inventory/adjustments`。

- [ ] **Step 1: 写服务失败测试：盘盈调用 receive、盘亏调用 issue；实际库存已不同于用户看到的系统数量时返回冲突；原因空白失败；整单生成调整单和流水。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=AdjustmentServiceTest`，确认失败。**
- [ ] **Step 3: 实现幂等事务和 `AD-yyyyMMdd-000001` 单号；调整流水使用当前平均成本，盘亏不得形成负库存。**
- [ ] **Step 4: 写前端失败测试，断言盘点数量、差异和原因校验；实现库存筛选、低库存视图、流水抽屉、调整弹窗，并按当前筛选结果导出含 UTF-8 BOM 的库存 CSV。**
- [ ] **Step 5: 运行后端测试、前端测试和类型检查。**
- [ ] **Step 6: 提交 `git add backend frontend && git commit -m "feat: add stock adjustments"`。**

### Task 3: 首页和报表查询 API

**Files:**
- Create: `backend/src/main/java/com/sportshop/report/ReportModels.java`
- Create: `backend/src/main/java/com/sportshop/report/ReportRepository.java`
- Create: `backend/src/main/java/com/sportshop/report/ReportService.java`
- Create: `backend/src/main/java/com/sportshop/report/ReportController.java`
- Create: `backend/src/test/java/com/sportshop/report/ReportServiceTest.java`

**Interfaces:**
- Produces: `DashboardView dashboard(LocalDate date)`、`SalesSummary sales(DateRange range)`、`List<ProductRanking> productRanking(DateRange range, int limit)`、`List<CategoryShare> categoryShare(DateRange range)`、`InboundSummary inbound(DateRange range)`、`InventoryValuation inventoryValuation()`、`List<LowStockItem> lowStock()`。
- HTTP under `/api/reports` and `GET /api/dashboard?date=`。

- [ ] **Step 1: 写失败测试数据：两笔销售、一笔部分退货、一次入库；断言销售额扣除退款、毛利扣除退货毛利、排行榜按净销量排序、库存金额按当前余额计算、低库存使用 `quantity <= warning_stock`。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=ReportServiceTest`，确认失败。**
- [ ] **Step 3: 使用参数化 SQL 实现只读 Repository；所有日期范围使用左闭右开 `[from, to)`；无数据时返回零而不是 null；分页排行榜使用稳定次序 `net_quantity DESC, sku_id ASC`。**
- [ ] **Step 4: 实现控制器的 ISO 日期校验和最大查询跨度 366 天；跨度超限返回 400。**
- [ ] **Step 5: 运行报表测试及全部后端测试。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add dashboard and reports API"`。**

### Task 4: 首页与统计报表界面

**Files:**
- Create: `frontend/src/modules/dashboard/api.ts`
- Create: `frontend/src/modules/dashboard/views/DashboardView.vue`
- Create: `frontend/src/modules/reports/api.ts`, `frontend/src/modules/reports/types.ts`
- Create: `frontend/src/modules/reports/views/ReportsView.vue`
- Create: `frontend/src/modules/reports/components/DateRangeFilter.vue`
- Create: `frontend/src/modules/reports/components/SalesTrendChart.vue`
- Create: `frontend/src/modules/reports/views/ReportsView.test.ts`

**Interfaces:**
- Consumes Task 3 APIs；图表输入只接受后端返回的日期与定点金额字符串。

- [ ] **Step 1: 写失败测试：默认日期为今天；切换日/月/自定义范围重新请求；超过 366 天阻止查询；空数据展示 0 和空状态；表格导出保持当前筛选条件。**
- [ ] **Step 2: 运行 `cd frontend && npm test -- ReportsView.test.ts`，确认失败。**
- [ ] **Step 3: 实现首页指标卡、低库存提示、最近单据和畅销排行；实现销售、毛利、进货、分类占比、库存金额和低库存报表。**
- [ ] **Step 4: 使用 ECharts 只渲染趋势和分类占比；金额格式统一为 `Intl.NumberFormat('zh-CN', {style:'currency', currency:'CNY'})`；CSV 导出使用 UTF-8 BOM。**
- [ ] **Step 5: 运行前端测试、类型检查和生产构建。**
- [ ] **Step 6: 提交 `git add frontend && git commit -m "feat: add dashboard and report views"`。**

### Task 5: 门店、支付方式、小票和操作日志

**Files:**
- Create: `backend/src/main/java/com/sportshop/settings/SettingsModels.java`
- Create: `backend/src/main/java/com/sportshop/settings/SettingsRepository.java`
- Create: `backend/src/main/java/com/sportshop/settings/SettingsService.java`
- Create: `backend/src/main/java/com/sportshop/settings/SettingsController.java`
- Create: `backend/src/main/java/com/sportshop/shared/audit/OperationLogService.java`
- Create: `backend/src/test/java/com/sportshop/settings/SettingsServiceTest.java`
- Create: `frontend/src/modules/settings/views/SettingsView.vue`
- Create: `frontend/src/modules/settings/views/SettingsView.test.ts`

**Interfaces:**
- HTTP: `GET/PUT /api/settings/store`、`GET/PUT /api/settings/receipt`、`GET/PUT /api/settings/document-numbering`、`GET/POST/PATCH /api/settings/payment-methods`、`GET /api/operation-logs`。

- [ ] **Step 1: 写后端失败测试：更新门店资料与小票设置；单号前缀只能包含大写字母且序列不能倒退；支付方式代码不可重复；已被收款引用的支付方式只能停用；关键操作成功与失败均记录类型、对象、时间、结果和设备信息。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=SettingsServiceTest`，确认失败。**
- [ ] **Step 3: 实现设置和审计服务；请求过滤器根据配置的设备名称和 User-Agent 生成设备摘要，不持久化 IP 地址；将入库、销售、退货、调整、备份恢复接入操作日志。**
- [ ] **Step 4: 写前端失败测试并实现门店、小票、支付方式和只读操作日志页面；无登录相关控件。**
- [ ] **Step 5: 运行全部后端和前端测试。**
- [ ] **Step 6: 提交 `git add backend frontend && git commit -m "feat: add store settings and audit log"`。**

### Task 6: SQLite 备份与安全恢复

**Files:**
- Create: `backend/src/main/java/com/sportshop/backup/BackupModels.java`
- Create: `backend/src/main/java/com/sportshop/backup/BackupService.java`
- Create: `backend/src/main/java/com/sportshop/backup/BackupController.java`
- Create: `backend/src/main/java/com/sportshop/backup/ReloadableDataSource.java`
- Create: `backend/src/test/java/com/sportshop/backup/BackupServiceTest.java`
- Create: `frontend/src/modules/settings/components/BackupPanel.vue`
- Create: `frontend/src/modules/settings/components/BackupPanel.test.ts`

**Interfaces:**
- Produces: `BackupView createBackup()`、`RestorePreview inspect(Path uploadedFile)`、`RestoreResult restore(UUID backupId, String confirmationText)`。
- HTTP: `POST /api/backups`、`GET /api/backups`、`POST /api/backups/{id}/restore-preview`、`POST /api/backups/{id}/restore`。

- [ ] **Step 1: 写失败测试：备份文件能通过 `PRAGMA integrity_check`；备份包含迁移版本；损坏文件和未知新版本被拒绝；恢复前自动创建 `pre-restore` 备份；确认文本不是“恢复数据”时拒绝。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=BackupServiceTest`，确认失败。**
- [ ] **Step 3: 使用 SQLite `VACUUM INTO` 创建到 `${SPORTSHOP_DATA_DIR}/backups` 的临时文件，完整性检查通过后原子改名；文件名使用 `sportshop-yyyyMMdd-HHmmss.db`；记录 SHA-256、大小和状态。**
- [ ] **Step 4: 实现恢复协调：进入维护状态、拒绝新写请求、创建保护备份、关闭连接池、原子替换数据库、重新初始化连接池并执行完整性检查；失败时恢复保护备份并记录 FAILED。**
- [ ] **Step 5: 写前端测试并实现备份列表、立即备份、恢复预检、二次确认输入和恢复结果提示。**
- [ ] **Step 6: 运行备份测试、全部后端测试、前端测试与类型检查。**
- [ ] **Step 7: 提交 `git add backend frontend && git commit -m "feat: add safe SQLite backup and restore"`。**

### Task 7: 单机发布与最终验收

**Files:**
- Create: `backend/src/main/java/com/sportshop/shared/web/SpaForwardController.java`
- Modify: `frontend/vite.config.ts`
- Modify: `backend/pom.xml`
- Create: `scripts/build-release.ps1`
- Create: `scripts/start.ps1`
- Create: `frontend/e2e/full-business-flow.spec.ts`
- Create: `docs/operations/windows-deployment.md`

**Interfaces:**
- Produces: `dist/sportshop/`，包含可执行 JAR、`start.ps1`、空 `data/` 与操作说明。

- [ ] **Step 1: 写 E2E：建分类 → 扫码快速建档 → 两次不同进价入库 → 验证移动平均成本 → 扫码销售 → 部分退货 → 库存调整 → 检查首页与报表 → 创建备份。**
- [ ] **Step 2: 运行完整 E2E，确认尚未发布集成时失败。**
- [ ] **Step 3: 配置 Maven 在打包前运行前端构建并复制 `frontend/dist` 到 Spring Boot 静态资源；SPA 非 `/api` 路径回退到 `index.html`；API 404 保持 JSON ProblemDetail。**
- [ ] **Step 4: 编写 `build-release.ps1`：检查 Java 21 与 Node 24、执行后端测试、前端测试、类型检查、E2E 和生产构建，最后复制发行文件；任何命令失败立即退出。**
- [ ] **Step 5: 编写 `start.ps1`：将数据目录解析为脚本目录下 `data`，创建目录后以 `java -jar` 启动；不得写入用户主目录。**
- [ ] **Step 6: 操作文档写明安装、启动、数据位置、扫码枪键盘模式、备份、恢复、升级前备份和故障排查。**
- [ ] **Step 7: 运行 `cd backend && mvn test`、`cd frontend && npm test -- --run`、`npm run type-check`、`npm run test:e2e`、`powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1`；确认发行目录可以从空数据目录启动并完成健康检查。**
- [ ] **Step 8: 提交 `git add backend frontend scripts docs && git commit -m "feat: package first sport shop release"`。**

## 最终完成定义

- 设计规格中的商品、入库、库存、零售、退货、报表、设置、日志和备份全部可用。
- 关键业务具有单元、集成和端到端测试。
- 单据、库存余额和库存流水始终一致。
- Windows 单机可以通过一个启动脚本运行，无需数据库服务器。
- 全量测试、类型检查、生产构建和发行验证全部通过。
