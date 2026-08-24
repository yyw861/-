package com.sportshop.inventory.adjustment;

import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentLineView;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentQuery;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentReceipt;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AdjustmentRepository {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    AdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String nextOrderNumber(LocalDate businessDate) {
        String prefix = "AD-" + businessDate.toString().replace("-", "") + "-";
        Optional<String> maximum = jdbc.sql("""
                        SELECT MAX(order_no) FROM stock_adjustment_order WHERE order_no LIKE :prefix
                        """).param("prefix", prefix + "%").query(String.class).optional();
        int next = maximum.filter(value -> !value.isBlank())
                .map(value -> Integer.parseInt(value.substring(prefix.length())) + 1).orElse(1);
        return prefix + "%06d".formatted(next);
    }

    void insertOrder(UUID id, String orderNo, String occurredAt, int totalLines, String createdAt) {
        jdbc.sql("""
                        INSERT INTO stock_adjustment_order
                            (id, order_no, occurred_at, total_lines, status, created_at)
                        VALUES (:id, :orderNo, :occurredAt, :totalLines, 'CONFIRMED', :createdAt)
                        """)
                .param("id", id.toString()).param("orderNo", orderNo).param("occurredAt", occurredAt)
                .param("totalLines", totalLines).param("createdAt", createdAt).update();
    }

    void insertLine(UUID id, UUID orderId, UUID skuId, int systemQuantity, int countedQuantity,
                    int differenceQuantity, BigDecimal unitCostSnapshot, String reason) {
        jdbc.sql("""
                        INSERT INTO stock_adjustment_line
                            (id, adjustment_order_id, sku_id, system_quantity, counted_quantity,
                             difference_quantity, unit_cost_snapshot, reason)
                        VALUES (:id, :orderId, :skuId, :systemQuantity, :countedQuantity,
                                :differenceQuantity, :unitCostSnapshot, :reason)
                        """)
                .param("id", id.toString()).param("orderId", orderId.toString())
                .param("skuId", skuId.toString()).param("systemQuantity", systemQuantity)
                .param("countedQuantity", countedQuantity).param("differenceQuantity", differenceQuantity)
                .param("unitCostSnapshot", unitCostSnapshot).param("reason", reason).update();
    }

    Optional<AdjustmentReceipt> findReceipt(UUID id) {
        return jdbc.sql("""
                        SELECT id, order_no, occurred_at, total_lines, status, created_at
                          FROM stock_adjustment_order WHERE id = :id
                        """).param("id", id.toString()).query((row, rowNumber) -> new AdjustmentReceipt(
                        UUID.fromString(row.getString("id")), row.getString("order_no"),
                        row.getString("occurred_at"), row.getInt("total_lines"), row.getString("status"),
                        row.getString("created_at"), findLines(id))).optional();
    }

    List<AdjustmentSummary> search(AdjustmentQuery query) {
        return jdbc.sql("""
                        SELECT id, order_no, occurred_at, total_lines, status, created_at
                          FROM stock_adjustment_order
                         WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                           AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                           AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                         ORDER BY julianday(occurred_at) DESC, order_no DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate())).param("orderNo", pattern(query.orderNo()))
                .param("limit", query.size()).param("offset", Math.toIntExact((long) query.page() * query.size()))
                .query((row, rowNumber) -> new AdjustmentSummary(UUID.fromString(row.getString("id")),
                        row.getString("order_no"), row.getString("occurred_at"), row.getInt("total_lines"),
                        row.getString("status"), row.getString("created_at"))).list();
    }

    long count(AdjustmentQuery query) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM stock_adjustment_order
                         WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                           AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                           AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                        """)
                .param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate())).param("orderNo", pattern(query.orderNo()))
                .query(Long.class).single();
    }

    private List<AdjustmentLineView> findLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT line.id, line.sku_id, sku.sku_code, sku.barcode, product.name AS product_name,
                               line.system_quantity, line.counted_quantity, line.difference_quantity,
                               line.unit_cost_snapshot, line.reason
                          FROM stock_adjustment_line line
                          JOIN product_sku sku ON sku.id = line.sku_id
                          JOIN product_spu product ON product.id = sku.spu_id
                         WHERE line.adjustment_order_id = :orderId
                         ORDER BY line.rowid
                        """).param("orderId", orderId.toString()).query((row, rowNumber) -> new AdjustmentLineView(
                        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("sku_id")),
                        row.getString("sku_code"), row.getString("barcode"), row.getString("product_name"),
                        row.getInt("system_quantity"), row.getInt("counted_quantity"),
                        row.getInt("difference_quantity"), row.getBigDecimal("unit_cost_snapshot").setScale(4),
                        row.getString("reason"))).list();
    }

    private static String fromInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(SHOP_ZONE).toInstant().toString();
    }

    private static String toExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant().toString();
    }

    private static String pattern(String orderNo) {
        return orderNo == null || orderNo.isBlank() ? null : "%" + orderNo.trim() + "%";
    }
}
