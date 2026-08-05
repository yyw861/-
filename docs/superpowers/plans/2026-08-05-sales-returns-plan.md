# 零售收银与原单退货 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已完成的商品与库存能力之上，交付扫码零售、优惠分摊、收款、库存扣减、销售查询和原单退货闭环。

**Architecture:** 销售模块负责定价、销售单和收款，退货模块只接受原销售明细并校验累计可退数量；二者均通过库存模块改变库存。所有确认操作使用幂等请求和单个数据库事务。

**Tech Stack:** 延续第一阶段的 Java 21、Spring Boot 4.1、SQLite、Vue 3、TypeScript、Element Plus、Vitest 和 Playwright。

## Global Constraints

- 依赖 `2026-08-05-foundation-catalog-inbound-plan.md` 全部完成。
- 不允许负库存；销售与退货确认后不可编辑或删除。
- 原价、优惠、实收、退款和明细分摊金额全部使用 2 位定点小数。
- 成本快照使用销售时库存平均成本的 4 位值。
- 退货必须关联原销售明细，累计退货数量不得超过原销售数量。

---

### Task 1: 销售与退货数据库结构

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__sales_and_returns.sql`
- Create: `backend/src/test/java/com/sportshop/sales/SalesSchemaMigrationTest.java`

**Interfaces:**
- Produces tables: `payment_method`, `sale_order`, `sale_line`, `payment_record`, `return_order`, `return_line`, `refund_record`。

- [ ] **Step 1: 写迁移失败测试，断言 7 张表存在；`sale_order.order_no`、`return_order.order_no` 唯一；`return_line.original_sale_line_id` 必填；默认支付方式包含 CASH、WECHAT、ALIPAY、BANK_CARD。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=SalesSchemaMigrationTest`，确认失败。**
- [ ] **Step 3: 编写 V2 SQL；销售明细保存 `list_unit_price`、`allocated_discount`、`actual_amount`、`cost_unit_snapshot`、`returned_quantity`；退货明细保存退款金额与原成本快照；所有外键启用级联限制而非级联删除。**
- [ ] **Step 4: 运行迁移测试与全部后端测试。**
- [ ] **Step 5: 提交 `git add backend && git commit -m "feat: add sales and returns schema"`。**

### Task 2: 确定性的整单优惠分摊

**Files:**
- Create: `backend/src/main/java/com/sportshop/sales/PricingAllocator.java`
- Create: `backend/src/test/java/com/sportshop/sales/PricingAllocatorTest.java`

**Interfaces:**
- Produces: `List<AllocatedLine> allocate(List<PricingLine> lines, BigDecimal orderDiscount)`；输入行含稳定 `lineId`、数量和标价，输出含原价金额、分摊优惠和实际金额。

- [ ] **Step 1: 写失败测试：100 与 50 两行分摊 15 得到 10 和 5；三条 0.01 分摊 0.01 时总分摊精确为 0.01 且尾差落到按 lineId 排序后的最后一行；优惠大于原价合计时报错。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=PricingAllocatorTest`，确认类不存在而失败。**
- [ ] **Step 3: 实现按优惠前金额占比分摊、每行 2 位 HALF_UP、最后一条有效明细吸收尾差；禁止负价格、空行和超额优惠。**
- [ ] **Step 4: 运行测试，确认所有分摊及合计断言通过。**
- [ ] **Step 5: 提交 `git add backend && git commit -m "feat: add deterministic sale pricing"`。**

### Task 3: 幂等销售确认事务

**Files:**
- Create: `backend/src/main/java/com/sportshop/sales/SalesModels.java`
- Create: `backend/src/main/java/com/sportshop/sales/SalesRepository.java`
- Create: `backend/src/main/java/com/sportshop/sales/SalesService.java`
- Create: `backend/src/main/java/com/sportshop/sales/SalesController.java`
- Create: `backend/src/test/java/com/sportshop/sales/SalesServiceTest.java`
- Create: `backend/src/test/java/com/sportshop/sales/SalesControllerTest.java`

**Interfaces:**
- Produces: `SaleReceipt checkout(CheckoutCommand command)`；command 含 requestId、折扣、`List<SaleLineInput(skuId, quantity)>` 和 `List<PaymentInput(methodCode, amount)>`。
- HTTP: `POST /api/sales`、`GET /api/sales`、`GET /api/sales/{id}`、`GET /api/sales/by-no/{orderNo}`。

- [ ] **Step 1: 写失败测试：售价来自当前 SKU；成本取当前库存平均成本；库存扣减且平均成本不变；支付合计必须等于实收；库存不足整单回滚；相同 requestId 返回同一销售单。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=SalesServiceTest`，确认失败。**
- [ ] **Step 3: 用 `@Transactional` 实现 `checkout`：加载并锁定所需库存、拒绝停用 SKU、合并重复 SKU、执行 PricingAllocator、校验支付、生成 `SO-yyyyMMdd-000001`、保存成本快照、调用 `InventoryService.issue`、记录幂等结果。**
- [ ] **Step 4: 写控制器测试验证正常 201、库存不足 409、校验错误 400、重复请求 200；实现查询分页参数 `from`、`to`、`orderNo`。**
- [ ] **Step 5: 运行销售测试及全部后端测试。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add retail checkout transaction"`。**

### Task 4: 原销售单退货事务

**Files:**
- Create: `backend/src/main/java/com/sportshop/returns/ReturnModels.java`
- Create: `backend/src/main/java/com/sportshop/returns/ReturnRepository.java`
- Create: `backend/src/main/java/com/sportshop/returns/ReturnService.java`
- Create: `backend/src/main/java/com/sportshop/returns/ReturnController.java`
- Create: `backend/src/test/java/com/sportshop/returns/ReturnServiceTest.java`
- Create: `backend/src/test/java/com/sportshop/returns/ReturnControllerTest.java`

**Interfaces:**
- Produces: `ReturnReceipt returnItems(ReturnCommand command)`；command 含 requestId、原销售单 ID、原因、退款方式和 `List<ReturnLineInput(originalSaleLineId, quantity)>`。
- HTTP: `POST /api/returns`、`GET /api/returns`、`GET /api/returns/{id}`。

- [ ] **Step 1: 写失败测试：部分退货更新累计已退数量；库存按原成本快照回补并重算平均成本；超额退货失败；引用其他销售单明细失败；最后一次退货结清该明细金额尾差；重复 requestId 不重复回补库存。**
- [ ] **Step 2: 运行 `cd backend && mvn test -Dtest=ReturnServiceTest`，确认失败。**
- [ ] **Step 3: 用 `@Transactional` 实现退货：锁定原销售明细；计算可退数量；非最后一次按 `actualAmount / soldQuantity` 取 2 位退款，最后一次使用 `actualAmount - previousRefunds`；生成 `RT-yyyyMMdd-000001`；调用 `InventoryService.receive` 并使用原成本快照。**
- [ ] **Step 4: 写控制器测试验证 201、超退 409、错误原单 400、重复请求 200；实现控制器和查询。**
- [ ] **Step 5: 运行退货测试及全部后端测试。**
- [ ] **Step 6: 提交 `git add backend && git commit -m "feat: add original-sale returns"`。**

### Task 5: 扫码收银界面

**Files:**
- Create: `frontend/src/modules/sales/api.ts`, `frontend/src/modules/sales/types.ts`
- Create: `frontend/src/modules/sales/stores/cart.ts`
- Create: `frontend/src/modules/sales/views/CheckoutView.vue`
- Create: `frontend/src/modules/sales/components/CheckoutCart.vue`
- Create: `frontend/src/modules/sales/components/PaymentDialog.vue`
- Create: `frontend/src/modules/sales/components/ReceiptDialog.vue`
- Create: `frontend/src/modules/sales/views/CheckoutView.test.ts`

**Interfaces:**
- Consumes catalog barcode lookup and `POST /api/sales`。
- Produces store actions: `scan`、`changeQuantity`、`setDiscount`、`checkout`、`clear`。

- [ ] **Step 1: 写失败测试：扫码加入购物车；重复扫码累加；停用或未知条码提示；数量超过页面库存时阻止提交；支付金额不等于实收时禁用确认；提交期间防止双击。**
- [ ] **Step 2: 运行 `cd frontend && npm test -- CheckoutView.test.ts`，确认失败。**
- [ ] **Step 3: 实现购物车、优惠预览和支付弹窗；页面金额仅用于预览，提交成功后以服务端回执替换；扫码输入始终自动重新聚焦。**
- [ ] **Step 4: 实现销售回执与小票打印样式，内容含门店、单号、时间、SKU、数量、成交金额、支付方式和实收。**
- [ ] **Step 5: 运行前端测试和类型检查。**
- [ ] **Step 6: 提交 `git add frontend && git commit -m "feat: add barcode checkout interface"`。**

### Task 6: 销售查询与原单退货界面

**Files:**
- Create: `frontend/src/modules/sales/views/SalesHistoryView.vue`
- Create: `frontend/src/modules/sales/views/SaleDetailView.vue`
- Create: `frontend/src/modules/returns/api.ts`, `frontend/src/modules/returns/types.ts`
- Create: `frontend/src/modules/returns/components/ReturnDialog.vue`
- Create: `frontend/src/modules/returns/components/ReturnDialog.test.ts`

**Interfaces:**
- Consumes sales detail and returns APIs。

- [ ] **Step 1: 写失败测试：只允许选择可退数量大于零的明细；数量上限等于可退数量；显示预计退款；成功后刷新销售详情并显示累计已退数量。**
- [ ] **Step 2: 运行 `cd frontend && npm test -- ReturnDialog.test.ts`，确认失败。**
- [ ] **Step 3: 实现按日期与单号查询、详情、小票补打和退货弹窗；退货请求使用新 UUID；失败时保留输入并展示服务端业务错误。**
- [ ] **Step 4: 运行全部前端测试与类型检查。**
- [ ] **Step 5: 提交 `git add frontend && git commit -m "feat: add sales history and returns UI"`。**

### Task 7: 销售退货端到端验收

**Files:**
- Create: `frontend/e2e/sales-return-flow.spec.ts`

**Interfaces:**
- Verifies: 已有库存 → 扫码销售 → 库存扣减 → 部分退货 → 库存回补 → 金额与成本一致。

- [ ] **Step 1: 写 Playwright 测试：使用库存 10、成本 100、售价 150 的 SKU，销售 2 件并优惠 30，断言实收 270、销售后库存 8；退货 1 件断言退款 135、库存 9、原明细已退 1。**
- [ ] **Step 2: 运行 `cd frontend && npm run test:e2e -- sales-return-flow.spec.ts`，确认失败。**
- [ ] **Step 3: 修正实际流程缺陷，不在 E2E 中绕过业务 API 或直接改数据库。**
- [ ] **Step 4: 运行 `cd backend && mvn test`、`cd frontend && npm test -- --run`、`npm run type-check`、`npm run test:e2e`，全部通过。**
- [ ] **Step 5: 提交 `git add frontend && git commit -m "test: cover retail sale and return flow"`。**

## 第二阶段完成定义

- 能扫码销售、优惠、收款并打印销售小票。
- 销售扣减库存且保存成本快照，库存不足时整单失败。
- 能从原销售单部分或整单退货，退款和库存回补准确。
- 重复提交不产生重复销售、退款或库存流水。
- 后端、前端和端到端测试全部通过。
