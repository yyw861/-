CREATE TABLE store_setting (
    id TEXT NOT NULL PRIMARY KEY CHECK (id = 'default'),
    store_name TEXT NOT NULL CHECK (TRIM(store_name) <> ''),
    phone TEXT,
    address TEXT,
    device_name TEXT NOT NULL CHECK (TRIM(device_name) <> ''),
    updated_at TEXT NOT NULL
);

INSERT INTO store_setting (id, store_name, phone, address, device_name, updated_at)
VALUES ('default', '体育商品门店', NULL, NULL, '默认收银台', CURRENT_TIMESTAMP);

CREATE TABLE receipt_setting (
    id TEXT NOT NULL PRIMARY KEY CHECK (id = 'default'),
    header_text TEXT,
    footer_text TEXT,
    show_phone INTEGER NOT NULL DEFAULT 1 CHECK (show_phone IN (0, 1)),
    show_address INTEGER NOT NULL DEFAULT 1 CHECK (show_address IN (0, 1)),
    paper_width INTEGER NOT NULL DEFAULT 58 CHECK (paper_width IN (58, 80)),
    updated_at TEXT NOT NULL
);

INSERT INTO receipt_setting
    (id, header_text, footer_text, show_phone, show_address, paper_width, updated_at)
VALUES ('default', NULL, '谢谢惠顾', 1, 1, 58, CURRENT_TIMESTAMP);

CREATE TABLE document_sequence (
    document_type TEXT NOT NULL PRIMARY KEY,
    prefix TEXT NOT NULL
        CHECK (prefix <> '' AND prefix NOT GLOB '*[^A-Z]*'),
    next_value INTEGER NOT NULL
        CHECK (typeof(next_value) = 'integer' AND next_value > 0),
    updated_at TEXT NOT NULL
);

INSERT INTO document_sequence (document_type, prefix, next_value, updated_at) VALUES
    ('INBOUND', 'IN', 1, CURRENT_TIMESTAMP),
    ('SALE', 'SO', 1, CURRENT_TIMESTAMP),
    ('RETURN', 'RT', 1, CURRENT_TIMESTAMP),
    ('ADJUSTMENT', 'AD', 1, CURRENT_TIMESTAMP);

CREATE TABLE operation_log (
    id TEXT NOT NULL PRIMARY KEY,
    operation_type TEXT NOT NULL CHECK (TRIM(operation_type) <> ''),
    object_type TEXT NOT NULL CHECK (TRIM(object_type) <> ''),
    object_id TEXT,
    occurred_at TEXT NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('SUCCESS', 'FAILED')),
    message TEXT,
    device_summary TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX ix_operation_log_occurred_at ON operation_log (occurred_at);
CREATE INDEX ix_operation_log_object ON operation_log (object_type, object_id);

CREATE TABLE stock_adjustment_order (
    id TEXT NOT NULL PRIMARY KEY,
    order_no TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    total_lines INTEGER NOT NULL
        CHECK (typeof(total_lines) = 'integer' AND total_lines > 0),
    status TEXT NOT NULL CHECK (status = 'CONFIRMED'),
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_stock_adjustment_order_order_no
    ON stock_adjustment_order (order_no);
CREATE INDEX ix_stock_adjustment_order_occurred_at
    ON stock_adjustment_order (occurred_at);

CREATE TABLE stock_adjustment_line (
    id TEXT NOT NULL PRIMARY KEY,
    adjustment_order_id TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    system_quantity INTEGER NOT NULL
        CHECK (typeof(system_quantity) = 'integer' AND system_quantity >= 0),
    counted_quantity INTEGER NOT NULL
        CHECK (typeof(counted_quantity) = 'integer' AND counted_quantity >= 0),
    difference_quantity INTEGER NOT NULL
        CHECK (typeof(difference_quantity) = 'integer'
               AND difference_quantity <> 0
               AND difference_quantity = counted_quantity - system_quantity),
    unit_cost_snapshot NUMERIC NOT NULL
        CHECK (unit_cost_snapshot >= 0
               AND unit_cost_snapshot = ROUND(unit_cost_snapshot, 4)),
    reason TEXT NOT NULL CHECK (TRIM(reason) <> ''),
    FOREIGN KEY (adjustment_order_id)
        REFERENCES stock_adjustment_order (id) ON DELETE RESTRICT,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id) ON DELETE RESTRICT
);

CREATE INDEX ix_stock_adjustment_line_order_id
    ON stock_adjustment_line (adjustment_order_id);
CREATE INDEX ix_stock_adjustment_line_sku_id
    ON stock_adjustment_line (sku_id);

CREATE TABLE backup_record (
    id TEXT NOT NULL PRIMARY KEY,
    file_name TEXT NOT NULL CHECK (TRIM(file_name) <> ''),
    file_path TEXT NOT NULL CHECK (TRIM(file_path) <> ''),
    sha256 TEXT,
    file_size INTEGER
        CHECK (file_size IS NULL OR (typeof(file_size) = 'integer' AND file_size >= 0)),
    backup_type TEXT NOT NULL CHECK (backup_type IN ('MANUAL', 'PRE_RESTORE')),
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    created_at TEXT NOT NULL,
    completed_at TEXT,
    error_message TEXT
);

CREATE INDEX ix_backup_record_created_at ON backup_record (created_at);
