package com.sportshop.inbound;

import com.sportshop.inbound.InboundModels.InboundLineView;
import com.sportshop.inbound.InboundModels.InboundQuery;
import com.sportshop.inbound.InboundModels.InboundReceipt;
import com.sportshop.inbound.InboundModels.InboundSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InboundRepository {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;

    InboundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertOrder(UUID id, String orderNo, String occurredAt, int totalQuantity, BigDecimal totalAmount,
                     String remark, String createdAt) {
        jdbc.sql("""
                        INSERT INTO inbound_order
                            (id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at)
                        VALUES (:id, :orderNo, :occurredAt, :totalQuantity, :totalAmount, :remark,
                                'CONFIRMED', :createdAt)
                        """)
                .param("id", id.toString()).param("orderNo", orderNo).param("occurredAt", occurredAt)
                .param("totalQuantity", totalQuantity).param("totalAmount", totalAmount)
                .param("remark", remark).param("createdAt", createdAt).update();
    }

    UUID insertLine(UUID orderId, UUID skuId, int quantity, BigDecimal unitCost, BigDecimal subtotal) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inbound_line (id, inbound_order_id, sku_id, quantity, unit_cost, subtotal)
                        VALUES (:id, :orderId, :skuId, :quantity, :unitCost, :subtotal)
                        """)
                .param("id", id.toString()).param("orderId", orderId.toString())
                .param("skuId", skuId.toString()).param("quantity", quantity)
                .param("unitCost", unitCost).param("subtotal", subtotal).update();
        return id;
    }

    Optional<InboundReceipt> findReceipt(UUID id) {
        return findOrder(id).map(order -> new InboundReceipt(order.id(), order.orderNo(), order.occurredAt(),
                order.totalQuantity(), order.totalAmount(), order.remark(), order.status(), order.createdAt(),
                findLines(id)));
    }

    List<InboundSummary> search(InboundQuery query) {
        return jdbc.sql("""
                        SELECT id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at
                          FROM inbound_order
                         WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                           AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                           AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                         ORDER BY julianday(occurred_at) DESC, order_no DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate()))
                .param("orderNo", pattern(query.orderNo())).param("limit", query.size())
                .param("offset", Math.toIntExact((long) query.page() * query.size()))
                .query(this::mapSummary).list();
    }

    long count(InboundQuery query) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM inbound_order
                         WHERE (:fromInstant IS NULL OR julianday(occurred_at) >= julianday(:fromInstant))
                           AND (:toExclusive IS NULL OR julianday(occurred_at) < julianday(:toExclusive))
                           AND (:orderNo IS NULL OR order_no LIKE :orderNo)
                        """)
                .param("fromInstant", fromInstant(query.fromDate()))
                .param("toExclusive", toExclusive(query.toDate()))
                .param("orderNo", pattern(query.orderNo())).query(Long.class).single();
    }

    private Optional<OrderRow> findOrder(UUID id) {
        return jdbc.sql("""
                        SELECT id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at
                          FROM inbound_order
                         WHERE id = :id
                        """)
                .param("id", id.toString()).query((row, rowNumber) -> new OrderRow(
                        UUID.fromString(row.getString("id")), row.getString("order_no"),
                        row.getString("occurred_at"), row.getInt("total_quantity"),
                        row.getBigDecimal("total_amount"), row.getString("remark"), row.getString("status"),
                        row.getString("created_at"))).optional();
    }

    private List<InboundLineView> findLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT line.id, line.sku_id, sku.sku_code, sku.barcode, product.name AS product_name,
                               line.quantity, line.unit_cost, line.subtotal
                          FROM inbound_line line
                          JOIN product_sku sku ON sku.id = line.sku_id
                          JOIN product_spu product ON product.id = sku.spu_id
                         WHERE line.inbound_order_id = :orderId
                         ORDER BY line.rowid
                        """)
                .param("orderId", orderId.toString()).query((row, rowNumber) -> new InboundLineView(
                        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("sku_id")),
                        row.getString("sku_code"), row.getString("barcode"), row.getString("product_name"),
                        row.getInt("quantity"), row.getBigDecimal("unit_cost"), row.getBigDecimal("subtotal")))
                .list();
    }

    private InboundSummary mapSummary(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new InboundSummary(UUID.fromString(row.getString("id")), row.getString("order_no"),
                row.getString("occurred_at"), row.getInt("total_quantity"), row.getBigDecimal("total_amount"),
                row.getString("remark"), row.getString("status"), row.getString("created_at"));
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

    private record OrderRow(UUID id, String orderNo, String occurredAt, int totalQuantity, BigDecimal totalAmount,
                            String remark, String status, String createdAt) {
    }
}
