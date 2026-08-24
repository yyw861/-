package com.sportshop.report;

import com.sportshop.report.ReportModels.CategoryShare;
import com.sportshop.report.ReportModels.InboundSummary;
import com.sportshop.report.ReportModels.InventoryValuation;
import com.sportshop.report.ReportModels.LowStockItem;
import com.sportshop.report.ReportModels.ProductRanking;
import com.sportshop.report.ReportModels.RecentDocument;
import com.sportshop.report.ReportModels.SalesTrendPoint;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReportRepository {
    private final JdbcClient jdbc;

    ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    SalesOrderTotals salesOrders(String from, String to) {
        return jdbc.sql("""
                SELECT COUNT(*) AS order_count, COALESCE(SUM(actual_amount), 0) AS gross_sales
                  FROM sale_order WHERE occurred_at >= :from AND occurred_at < :to
                """).param("from", from).param("to", to).query((rs, n) -> new SalesOrderTotals(
                rs.getLong("order_count"), rs.getBigDecimal("gross_sales"))).single();
    }

    LineTotals saleLines(String from, String to) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(line.quantity), 0) AS quantity,
                       COALESCE(SUM(line.actual_amount - line.cost_unit_snapshot * line.quantity), 0) AS profit
                  FROM sale_line line JOIN sale_order sale ON sale.id = line.sale_order_id
                 WHERE sale.occurred_at >= :from AND sale.occurred_at < :to
                """).param("from", from).param("to", to).query((rs, n) -> new LineTotals(
                rs.getLong("quantity"), rs.getBigDecimal("profit"))).single();
    }

    ReturnTotals returns(String from, String to) {
        return jdbc.sql("""
                SELECT
                    (SELECT COALESCE(SUM(ret.refund_amount), 0) FROM return_order ret
                      WHERE ret.occurred_at >= :from AND ret.occurred_at < :to) AS refund,
                    (SELECT COALESCE(SUM(line.quantity), 0)
                       FROM return_line line JOIN return_order ret ON ret.id = line.return_order_id
                      WHERE ret.occurred_at >= :from AND ret.occurred_at < :to) AS quantity,
                    (SELECT COALESCE(SUM(line.refund_amount - line.cost_unit_snapshot * line.quantity), 0)
                       FROM return_line line JOIN return_order ret ON ret.id = line.return_order_id
                      WHERE ret.occurred_at >= :from AND ret.occurred_at < :to) AS profit
                """).param("from", from).param("to", to).query((rs, n) -> new ReturnTotals(
                rs.getBigDecimal("refund"), rs.getLong("quantity"), rs.getBigDecimal("profit"))).single();
    }

    List<SalesTrendPoint> salesTrend(String from, String to) {
        return jdbc.sql("""
                SELECT business_date, SUM(net_sales) AS net_sales, SUM(profit) AS profit FROM (
                    SELECT date(datetime(sale.occurred_at, '+8 hours')) AS business_date,
                           SUM(line.actual_amount) AS net_sales,
                           SUM(line.actual_amount - line.cost_unit_snapshot * line.quantity) AS profit
                      FROM sale_order sale JOIN sale_line line ON line.sale_order_id = sale.id
                     WHERE sale.occurred_at >= :from AND sale.occurred_at < :to GROUP BY business_date
                    UNION ALL
                    SELECT date(datetime(ret.occurred_at, '+8 hours')) AS business_date,
                           -SUM(line.refund_amount) AS net_sales,
                           -SUM(line.refund_amount - line.cost_unit_snapshot * line.quantity) AS profit
                      FROM return_order ret JOIN return_line line ON line.return_order_id = ret.id
                     WHERE ret.occurred_at >= :from AND ret.occurred_at < :to GROUP BY business_date
                ) GROUP BY business_date ORDER BY business_date
                """).param("from", from).param("to", to).query((rs, n) -> new SalesTrendPoint(
                LocalDate.parse(rs.getString("business_date")), rs.getBigDecimal("net_sales"),
                rs.getBigDecimal("profit"))).list();
    }

    List<ProductRanking> productRanking(String from, String to, int limit) {
        return jdbc.sql("""
                SELECT sku.id AS sku_id, sku.sku_code, sku.barcode, product.name AS product_name,
                       SUM(data.gross_quantity) AS gross_quantity,
                       SUM(data.returned_quantity) AS returned_quantity,
                       SUM(data.net_quantity) AS net_quantity, SUM(data.net_sales) AS net_sales
                  FROM (
                    SELECT line.sku_id, line.quantity AS gross_quantity, 0 AS returned_quantity,
                           line.quantity AS net_quantity, line.actual_amount AS net_sales
                      FROM sale_line line JOIN sale_order sale ON sale.id = line.sale_order_id
                     WHERE sale.occurred_at >= :from AND sale.occurred_at < :to
                    UNION ALL
                    SELECT line.sku_id, 0, line.quantity, -line.quantity, -line.refund_amount
                      FROM return_line line JOIN return_order ret ON ret.id = line.return_order_id
                     WHERE ret.occurred_at >= :from AND ret.occurred_at < :to
                  ) data JOIN product_sku sku ON sku.id = data.sku_id
                         JOIN product_spu product ON product.id = sku.spu_id
                 GROUP BY sku.id, sku.sku_code, sku.barcode, product.name
                 ORDER BY net_quantity DESC, sku.id ASC LIMIT :limit
                """).param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> new ProductRanking(uuid(rs, "sku_id"), rs.getString("sku_code"),
                        rs.getString("barcode"), rs.getString("product_name"), rs.getLong("gross_quantity"),
                        rs.getLong("returned_quantity"), rs.getLong("net_quantity"),
                        rs.getBigDecimal("net_sales"))).list();
    }

    List<CategoryShare> categoryShare(String from, String to) {
        return jdbc.sql("""
                SELECT category.id AS category_id, category.name AS category_name,
                       SUM(data.net_sales) AS net_sales
                  FROM (
                    SELECT line.sku_id, line.actual_amount AS net_sales
                      FROM sale_line line JOIN sale_order sale ON sale.id = line.sale_order_id
                     WHERE sale.occurred_at >= :from AND sale.occurred_at < :to
                    UNION ALL
                    SELECT line.sku_id, -line.refund_amount
                      FROM return_line line JOIN return_order ret ON ret.id = line.return_order_id
                     WHERE ret.occurred_at >= :from AND ret.occurred_at < :to
                  ) data JOIN product_sku sku ON sku.id = data.sku_id
                         JOIN product_spu product ON product.id = sku.spu_id
                         JOIN category category ON category.id = product.category_id
                 GROUP BY category.id, category.name
                 ORDER BY net_sales DESC, category.id ASC
                """).param("from", from).param("to", to).query((rs, n) -> new CategoryShare(
                uuid(rs, "category_id"), rs.getString("category_name"), rs.getBigDecimal("net_sales"))).list();
    }

    InboundSummary inbound(String from, String to) {
        return jdbc.sql("""
                SELECT COUNT(*) AS order_count, COALESCE(SUM(total_quantity), 0) AS total_quantity,
                       COALESCE(SUM(total_amount), 0) AS total_amount
                  FROM inbound_order WHERE occurred_at >= :from AND occurred_at < :to
                """).param("from", from).param("to", to).query((rs, n) -> new InboundSummary(
                rs.getLong("order_count"), rs.getLong("total_quantity"), rs.getBigDecimal("total_amount"))).single();
    }

    InventoryValuation inventoryValuation() {
        return jdbc.sql("""
                SELECT COUNT(*) AS sku_count, COALESCE(SUM(quantity), 0) AS total_quantity,
                       COALESCE(SUM(quantity * average_cost), 0) AS total_cost FROM inventory_balance
                """).query((rs, n) -> new InventoryValuation(rs.getLong("sku_count"),
                rs.getLong("total_quantity"), rs.getBigDecimal("total_cost"))).single();
    }

    List<LowStockItem> lowStock() {
        return jdbc.sql("""
                SELECT sku.id AS sku_id, sku.sku_code, sku.barcode, product.name AS product_name,
                       balance.quantity, sku.warning_stock
                  FROM inventory_balance balance JOIN product_sku sku ON sku.id = balance.sku_id
                       JOIN product_spu product ON product.id = sku.spu_id
                 WHERE balance.quantity <= sku.warning_stock AND sku.enabled = 1 AND product.enabled = 1
                 ORDER BY balance.quantity - sku.warning_stock ASC, sku.id ASC
                """).query((rs, n) -> new LowStockItem(uuid(rs, "sku_id"), rs.getString("sku_code"),
                rs.getString("barcode"), rs.getString("product_name"), rs.getInt("quantity"),
                rs.getInt("warning_stock"))).list();
    }

    List<RecentDocument> recentDocuments(String from, String to, int limit) {
        return jdbc.sql("""
                SELECT document_type, id, order_no, occurred_at, amount FROM (
                    SELECT 'SALE' AS document_type, id, order_no, occurred_at, actual_amount AS amount
                      FROM sale_order WHERE occurred_at >= :from AND occurred_at < :to
                    UNION ALL
                    SELECT 'INBOUND', id, order_no, occurred_at, total_amount
                      FROM inbound_order WHERE occurred_at >= :from AND occurred_at < :to
                ) ORDER BY occurred_at DESC, order_no DESC LIMIT :limit
                """).param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> new RecentDocument(rs.getString("document_type"), uuid(rs, "id"),
                        rs.getString("order_no"), rs.getString("occurred_at"), rs.getBigDecimal("amount"))).list();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    record SalesOrderTotals(long orderCount, BigDecimal grossSales) {}
    record LineTotals(long quantity, BigDecimal profit) {}
    record ReturnTotals(BigDecimal refund, long quantity, BigDecimal profit) {}
}
