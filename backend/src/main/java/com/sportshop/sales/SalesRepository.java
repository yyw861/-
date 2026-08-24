package com.sportshop.sales;

import com.sportshop.sales.SalesModels.PaymentView;
import com.sportshop.sales.SalesModels.SaleLineView;
import com.sportshop.sales.SalesModels.SaleReceipt;
import com.sportshop.sales.SalesModels.SaleQuery;
import com.sportshop.sales.SalesModels.SaleSummary;
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
class SalesRepository {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;

    SalesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String nextOrderNumber(LocalDate businessDate) {
        String prefix = "SO-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer next = jdbc.sql("SELECT COALESCE(MAX(CAST(SUBSTR(order_no, 13) AS INTEGER)), 0) + 1 " +
                        "FROM sale_order WHERE order_no LIKE :prefix")
                .param("prefix", prefix + "%").query(Integer.class).single();
        return prefix + "%06d".formatted(next);
    }

    void insertOrder(UUID id, String orderNo, String occurredAt, BigDecimal originalAmount,
                     BigDecimal discountAmount, BigDecimal actualAmount, String remark) {
        jdbc.sql("""
                INSERT INTO sale_order
                    (id, order_no, occurred_at, original_amount, discount_amount, actual_amount,
                     status, remark, created_at)
                VALUES (:id, :orderNo, :occurredAt, :original, :discount, :actual,
                        'CONFIRMED', :remark, :occurredAt)
                """).param("id", id.toString()).param("orderNo", orderNo).param("occurredAt", occurredAt)
                .param("original", originalAmount).param("discount", discountAmount).param("actual", actualAmount)
                .param("remark", remark).update();
    }

    void insertLine(UUID id, UUID orderId, UUID skuId, int quantity, BigDecimal listUnitPrice,
                    BigDecimal allocatedDiscount, BigDecimal actualAmount, BigDecimal costSnapshot) {
        jdbc.sql("""
                INSERT INTO sale_line
                    (id, sale_order_id, sku_id, quantity, list_unit_price, allocated_discount,
                     actual_amount, cost_unit_snapshot, returned_quantity)
                VALUES (:id, :orderId, :skuId, :quantity, :price, :discount, :actual, :cost, 0)
                """).param("id", id.toString()).param("orderId", orderId.toString())
                .param("skuId", skuId.toString()).param("quantity", quantity).param("price", listUnitPrice)
                .param("discount", allocatedDiscount).param("actual", actualAmount).param("cost", costSnapshot)
                .update();
    }

    void insertPayment(UUID id, UUID orderId, String methodCode, BigDecimal amount, String occurredAt) {
        jdbc.sql("""
                INSERT INTO payment_record (id, sale_order_id, payment_method_code, amount, occurred_at)
                VALUES (:id, :orderId, :method, :amount, :occurredAt)
                """).param("id", id.toString()).param("orderId", orderId.toString())
                .param("method", methodCode).param("amount", amount).param("occurredAt", occurredAt).update();
    }

    boolean enabledPaymentMethod(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM payment_method WHERE code = :code AND enabled = 1")
                .param("code", code).query(Integer.class).single() == 1;
    }

    Optional<SaleReceipt> findReceipt(UUID id) {
        return jdbc.sql("""
                SELECT id, order_no, occurred_at, original_amount, discount_amount, actual_amount,
                       status, remark, created_at
                  FROM sale_order WHERE id = :id
                """).param("id", id.toString()).query((row, n) -> new SaleReceipt(
                        UUID.fromString(row.getString("id")), row.getString("order_no"), row.getString("occurred_at"),
                        row.getBigDecimal("original_amount"), row.getBigDecimal("discount_amount"),
                        row.getBigDecimal("actual_amount"), row.getString("status"), row.getString("remark"),
                        row.getString("created_at"), lines(id), payments(id))).optional();
    }

    Optional<SaleReceipt> findReceiptByOrderNo(String orderNo) {
        return jdbc.sql("SELECT id FROM sale_order WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(String.class).optional()
                .flatMap(id -> findReceipt(UUID.fromString(id)));
    }

    List<SaleSummary> search(SaleQuery query) {
        return jdbc.sql("""
                SELECT id, order_no, occurred_at, actual_amount, status
                  FROM sale_order
                 WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                   AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                   AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                 ORDER BY julianday(occurred_at) DESC, order_no DESC
                 LIMIT :limit OFFSET :offset
                """).param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate())).param("orderNo", pattern(query.orderNo()))
                .param("limit", query.size()).param("offset", Math.toIntExact((long) query.page() * query.size()))
                .query((row, n) -> new SaleSummary(UUID.fromString(row.getString("id")), row.getString("order_no"),
                        row.getString("occurred_at"), row.getBigDecimal("actual_amount"), row.getString("status")))
                .list();
    }

    long count(SaleQuery query) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM sale_order
                 WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                   AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                   AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                """).param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate())).param("orderNo", pattern(query.orderNo()))
                .query(Long.class).single();
    }

    private static String fromInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(SHOP_ZONE).toInstant().toString();
    }

    private static String toExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant().toString();
    }

    private static String pattern(String orderNo) {
        return orderNo == null ? null : "%" + orderNo + "%";
    }

    private List<SaleLineView> lines(UUID orderId) {
        return jdbc.sql("""
                SELECT line.id, line.sku_id, sku.sku_code, sku.barcode, line.quantity,
                       line.list_unit_price, line.allocated_discount, line.actual_amount,
                       line.cost_unit_snapshot, line.returned_quantity
                  FROM sale_line line JOIN product_sku sku ON sku.id = line.sku_id
                 WHERE line.sale_order_id = :orderId ORDER BY line.rowid
                """).param("orderId", orderId.toString()).query((row, n) -> new SaleLineView(
                        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("sku_id")),
                        row.getString("sku_code"), row.getString("barcode"), row.getInt("quantity"),
                        row.getBigDecimal("list_unit_price"), row.getBigDecimal("allocated_discount"),
                        row.getBigDecimal("actual_amount"), row.getBigDecimal("cost_unit_snapshot"),
                        row.getInt("returned_quantity"))).list();
    }

    private List<PaymentView> payments(UUID orderId) {
        return jdbc.sql("""
                SELECT id, payment_method_code, amount, occurred_at FROM payment_record
                 WHERE sale_order_id = :orderId ORDER BY rowid
                """).param("orderId", orderId.toString()).query((row, n) -> new PaymentView(
                        UUID.fromString(row.getString("id")), row.getString("payment_method_code"),
                        row.getBigDecimal("amount"), row.getString("occurred_at"))).list();
    }
}
