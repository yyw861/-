package com.sportshop.returns;

import com.sportshop.returns.ReturnModels.RefundView;
import com.sportshop.returns.ReturnModels.ReturnLineView;
import com.sportshop.returns.ReturnModels.ReturnReceipt;
import com.sportshop.returns.ReturnModels.ReturnQuery;
import com.sportshop.returns.ReturnModels.ReturnSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReturnRepository {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    ReturnRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    String nextOrderNumber(LocalDate date) {
        String prefix = "RT-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer next = jdbc.sql("SELECT COALESCE(MAX(CAST(SUBSTR(order_no, 13) AS INTEGER)), 0) + 1 "
                        + "FROM return_order WHERE order_no LIKE :prefix")
                .param("prefix", prefix + "%").query(Integer.class).single();
        return prefix + "%06d".formatted(next);
    }

    boolean enabledPaymentMethod(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM payment_method WHERE code = :code AND enabled = 1")
                .param("code", code).query(Integer.class).single() == 1;
    }

    Optional<SaleRow> findSale(UUID id) {
        return jdbc.sql("SELECT id, order_no FROM sale_order WHERE id = :id")
                .param("id", id.toString()).query((row, n) -> new SaleRow(UUID.fromString(row.getString("id")),
                        row.getString("order_no"))).optional();
    }

    Optional<SaleLineRow> findSaleLine(UUID lineId) {
        return jdbc.sql("""
                SELECT id, sale_order_id, sku_id, quantity, actual_amount, cost_unit_snapshot, returned_quantity
                  FROM sale_line WHERE id = :id
                """).param("id", lineId.toString()).query((row, n) -> new SaleLineRow(
                        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("sale_order_id")),
                        UUID.fromString(row.getString("sku_id")), row.getInt("quantity"),
                        row.getBigDecimal("actual_amount"), row.getBigDecimal("cost_unit_snapshot"),
                        row.getInt("returned_quantity"))).optional();
    }

    BigDecimal previousRefunds(UUID saleLineId) {
        return jdbc.sql("SELECT COALESCE(SUM(refund_amount), 0) FROM return_line WHERE original_sale_line_id = :id")
                .param("id", saleLineId.toString()).query(BigDecimal.class).single().setScale(2);
    }

    void insertOrder(UUID id, String orderNo, UUID saleId, String occurredAt, BigDecimal refundAmount,
                     String method, String reason) {
        jdbc.sql("""
                INSERT INTO return_order
                    (id, order_no, original_sale_order_id, occurred_at, refund_amount,
                     refund_method_code, reason, status, created_at)
                VALUES (:id, :orderNo, :saleId, :occurredAt, :amount, :method, :reason, 'CONFIRMED', :occurredAt)
                """).param("id", id.toString()).param("orderNo", orderNo).param("saleId", saleId.toString())
                .param("occurredAt", occurredAt).param("amount", refundAmount).param("method", method)
                .param("reason", reason).update();
    }

    void insertLine(UUID id, UUID returnId, SaleLineRow line, int quantity, BigDecimal refundAmount) {
        jdbc.sql("""
                INSERT INTO return_line
                    (id, return_order_id, original_sale_line_id, sku_id, quantity, refund_amount, cost_unit_snapshot)
                VALUES (:id, :returnId, :saleLineId, :skuId, :quantity, :refund, :cost)
                """).param("id", id.toString()).param("returnId", returnId.toString())
                .param("saleLineId", line.id().toString()).param("skuId", line.skuId().toString())
                .param("quantity", quantity).param("refund", refundAmount).param("cost", line.costSnapshot()).update();
    }

    boolean addReturnedQuantity(UUID lineId, int quantity) {
        return jdbc.sql("""
                UPDATE sale_line SET returned_quantity = returned_quantity + :quantity
                 WHERE id = :id AND returned_quantity + :quantity <= quantity
                """).param("quantity", quantity).param("id", lineId.toString()).update() == 1;
    }

    void updateSaleStatus(UUID saleId) {
        jdbc.sql("""
                UPDATE sale_order SET status = CASE
                    WHEN NOT EXISTS (SELECT 1 FROM sale_line WHERE sale_order_id = :saleId AND returned_quantity < quantity)
                        THEN 'RETURNED'
                    ELSE 'PARTIALLY_RETURNED' END
                 WHERE id = :saleId
                """).param("saleId", saleId.toString()).update();
    }

    void insertRefund(UUID id, UUID returnId, String method, BigDecimal amount, String occurredAt) {
        jdbc.sql("""
                INSERT INTO refund_record (id, return_order_id, payment_method_code, amount, occurred_at)
                VALUES (:id, :returnId, :method, :amount, :occurredAt)
                """).param("id", id.toString()).param("returnId", returnId.toString()).param("method", method)
                .param("amount", amount).param("occurredAt", occurredAt).update();
    }

    Optional<ReturnReceipt> findReceipt(UUID id) {
        return jdbc.sql("""
                SELECT r.id, r.order_no, r.original_sale_order_id, s.order_no AS sale_order_no,
                       r.occurred_at, r.refund_amount, r.refund_method_code, r.reason, r.status, r.created_at
                  FROM return_order r JOIN sale_order s ON s.id = r.original_sale_order_id WHERE r.id = :id
                """).param("id", id.toString()).query((row, n) -> new ReturnReceipt(
                        UUID.fromString(row.getString("id")), row.getString("order_no"),
                        UUID.fromString(row.getString("original_sale_order_id")), row.getString("sale_order_no"),
                        row.getString("occurred_at"), row.getBigDecimal("refund_amount"),
                        row.getString("refund_method_code"), row.getString("reason"), row.getString("status"),
                        row.getString("created_at"), lines(id), refund(id))).optional();
    }

    List<ReturnSummary> search(ReturnQuery query) {
        return jdbc.sql("""
                SELECT r.id, r.order_no, s.order_no AS sale_order_no, r.occurred_at, r.refund_amount, r.status
                  FROM return_order r JOIN sale_order s ON s.id = r.original_sale_order_id
                 WHERE (:fromInstant IS NULL OR julianday(r.occurred_at) >= julianday(:fromInstant))
                   AND (:toExclusive IS NULL OR julianday(r.occurred_at) < julianday(:toExclusive))
                   AND (:orderNo IS NULL OR r.order_no LIKE :orderNo)
                 ORDER BY julianday(r.occurred_at) DESC, r.order_no DESC LIMIT :limit OFFSET :offset
                """).param("fromInstant", fromInstant(query.fromDate())).param("toExclusive", toExclusive(query.toDate()))
                .param("orderNo", pattern(query.orderNo())).param("limit", query.size())
                .param("offset", Math.toIntExact((long) query.page() * query.size()))
                .query((row, n) -> new ReturnSummary(UUID.fromString(row.getString("id")), row.getString("order_no"),
                        row.getString("sale_order_no"), row.getString("occurred_at"),
                        row.getBigDecimal("refund_amount"), row.getString("status"))).list();
    }

    long count(ReturnQuery query) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM return_order r
                 WHERE (:fromInstant IS NULL OR julianday(r.occurred_at) >= julianday(:fromInstant))
                   AND (:toExclusive IS NULL OR julianday(r.occurred_at) < julianday(:toExclusive))
                   AND (:orderNo IS NULL OR r.order_no LIKE :orderNo)
                """).param("fromInstant", fromInstant(query.fromDate())).param("toExclusive", toExclusive(query.toDate()))
                .param("orderNo", pattern(query.orderNo())).query(Long.class).single();
    }

    private static String fromInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(SHOP_ZONE).toInstant().toString();
    }
    private static String toExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant().toString();
    }
    private static String pattern(String orderNo) { return orderNo == null ? null : "%" + orderNo + "%"; }

    private List<ReturnLineView> lines(UUID returnId) {
        return jdbc.sql("""
                SELECT l.id, l.original_sale_line_id, l.sku_id, s.sku_code, s.barcode,
                       l.quantity, l.refund_amount, l.cost_unit_snapshot
                  FROM return_line l JOIN product_sku s ON s.id = l.sku_id
                 WHERE l.return_order_id = :id ORDER BY l.rowid
                """).param("id", returnId.toString()).query((row, n) -> new ReturnLineView(
                        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("original_sale_line_id")),
                        UUID.fromString(row.getString("sku_id")), row.getString("sku_code"), row.getString("barcode"),
                        row.getInt("quantity"), row.getBigDecimal("refund_amount"),
                        row.getBigDecimal("cost_unit_snapshot"))).list();
    }

    private RefundView refund(UUID returnId) {
        return jdbc.sql("SELECT id, payment_method_code, amount, occurred_at FROM refund_record WHERE return_order_id = :id")
                .param("id", returnId.toString()).query((row, n) -> new RefundView(
                        UUID.fromString(row.getString("id")), row.getString("payment_method_code"),
                        row.getBigDecimal("amount"), row.getString("occurred_at"))).single();
    }

    record SaleRow(UUID id, String orderNo) {}
    record SaleLineRow(UUID id, UUID saleId, UUID skuId, int quantity, BigDecimal actualAmount,
                       BigDecimal costSnapshot, int returnedQuantity) {}
}
