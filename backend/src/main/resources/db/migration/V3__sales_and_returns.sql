CREATE TABLE payment_method (
    code TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (typeof(sort_order) = 'integer')
);

INSERT INTO payment_method (code, name, enabled, sort_order) VALUES
    ('CASH', '现金', 1, 10),
    ('WECHAT', '微信', 1, 20),
    ('ALIPAY', '支付宝', 1, 30),
    ('BANK_CARD', '银行卡', 1, 40);

CREATE TABLE sale_order (
    id TEXT NOT NULL PRIMARY KEY,
    order_no TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    original_amount NUMERIC NOT NULL
        CHECK (original_amount >= 0 AND original_amount = ROUND(original_amount, 2)),
    discount_amount NUMERIC NOT NULL
        CHECK (discount_amount >= 0 AND discount_amount = ROUND(discount_amount, 2)),
    actual_amount NUMERIC NOT NULL
        CHECK (actual_amount >= 0 AND actual_amount = ROUND(actual_amount, 2)
               AND actual_amount = original_amount - discount_amount),
    status TEXT NOT NULL CHECK (status IN ('CONFIRMED', 'PARTIALLY_RETURNED', 'RETURNED')),
    remark TEXT,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_sale_order_order_no ON sale_order (order_no);
CREATE INDEX ix_sale_order_occurred_at ON sale_order (occurred_at);

CREATE TABLE sale_line (
    id TEXT NOT NULL PRIMARY KEY,
    sale_order_id TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity > 0),
    list_unit_price NUMERIC NOT NULL
        CHECK (list_unit_price >= 0 AND list_unit_price = ROUND(list_unit_price, 2)),
    allocated_discount NUMERIC NOT NULL
        CHECK (allocated_discount >= 0 AND allocated_discount = ROUND(allocated_discount, 2)),
    actual_amount NUMERIC NOT NULL
        CHECK (actual_amount >= 0 AND actual_amount = ROUND(actual_amount, 2)),
    cost_unit_snapshot NUMERIC NOT NULL
        CHECK (cost_unit_snapshot >= 0 AND cost_unit_snapshot = ROUND(cost_unit_snapshot, 4)),
    returned_quantity INTEGER NOT NULL DEFAULT 0
        CHECK (typeof(returned_quantity) = 'integer'
               AND returned_quantity >= 0 AND returned_quantity <= quantity),
    FOREIGN KEY (sale_order_id) REFERENCES sale_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id) ON DELETE RESTRICT
);

CREATE INDEX ix_sale_line_sale_order_id ON sale_line (sale_order_id);
CREATE INDEX ix_sale_line_sku_id ON sale_line (sku_id);

CREATE TABLE payment_record (
    id TEXT NOT NULL PRIMARY KEY,
    sale_order_id TEXT NOT NULL,
    payment_method_code TEXT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount >= 0 AND amount = ROUND(amount, 2)),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (sale_order_id) REFERENCES sale_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (payment_method_code) REFERENCES payment_method (code) ON DELETE RESTRICT
);

CREATE INDEX ix_payment_record_sale_order_id ON payment_record (sale_order_id);

CREATE TABLE return_order (
    id TEXT NOT NULL PRIMARY KEY,
    order_no TEXT NOT NULL,
    original_sale_order_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    refund_amount NUMERIC NOT NULL
        CHECK (refund_amount >= 0 AND refund_amount = ROUND(refund_amount, 2)),
    refund_method_code TEXT NOT NULL,
    reason TEXT,
    status TEXT NOT NULL CHECK (status = 'CONFIRMED'),
    created_at TEXT NOT NULL,
    FOREIGN KEY (original_sale_order_id) REFERENCES sale_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (refund_method_code) REFERENCES payment_method (code) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_return_order_order_no ON return_order (order_no);
CREATE INDEX ix_return_order_original_sale_order_id ON return_order (original_sale_order_id);
CREATE INDEX ix_return_order_occurred_at ON return_order (occurred_at);

CREATE TABLE return_line (
    id TEXT NOT NULL PRIMARY KEY,
    return_order_id TEXT NOT NULL,
    original_sale_line_id TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity > 0),
    refund_amount NUMERIC NOT NULL
        CHECK (refund_amount >= 0 AND refund_amount = ROUND(refund_amount, 2)),
    cost_unit_snapshot NUMERIC NOT NULL
        CHECK (cost_unit_snapshot >= 0 AND cost_unit_snapshot = ROUND(cost_unit_snapshot, 4)),
    FOREIGN KEY (return_order_id) REFERENCES return_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (original_sale_line_id) REFERENCES sale_line (id) ON DELETE RESTRICT,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id) ON DELETE RESTRICT
);

CREATE INDEX ix_return_line_return_order_id ON return_line (return_order_id);
CREATE INDEX ix_return_line_original_sale_line_id ON return_line (original_sale_line_id);

CREATE TABLE refund_record (
    id TEXT NOT NULL PRIMARY KEY,
    return_order_id TEXT NOT NULL,
    payment_method_code TEXT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount >= 0 AND amount = ROUND(amount, 2)),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (return_order_id) REFERENCES return_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (payment_method_code) REFERENCES payment_method (code) ON DELETE RESTRICT
);

CREATE INDEX ix_refund_record_return_order_id ON refund_record (return_order_id);
