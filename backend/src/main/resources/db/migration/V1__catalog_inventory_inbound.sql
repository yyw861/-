CREATE TABLE category (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_category_name ON category (name);

CREATE TABLE brand (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    remark TEXT,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_brand_name ON brand (name);

CREATE TABLE product_spu (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    category_id TEXT NOT NULL,
    brand_id TEXT NOT NULL,
    image_url TEXT,
    description TEXT,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (category_id) REFERENCES category (id),
    FOREIGN KEY (brand_id) REFERENCES brand (id)
);

CREATE INDEX ix_product_spu_category_id ON product_spu (category_id);
CREATE INDEX ix_product_spu_brand_id ON product_spu (brand_id);

CREATE TABLE product_sku (
    id TEXT NOT NULL PRIMARY KEY,
    spu_id TEXT NOT NULL,
    sku_code TEXT NOT NULL,
    barcode TEXT NOT NULL,
    retail_price NUMERIC NOT NULL CHECK (retail_price >= 0 AND retail_price = ROUND(retail_price, 2)),
    warning_stock INTEGER NOT NULL DEFAULT 0 CHECK (typeof(warning_stock) = 'integer' AND warning_stock >= 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (spu_id) REFERENCES product_spu (id)
);

CREATE UNIQUE INDEX ux_product_sku_barcode ON product_sku (barcode);
CREATE UNIQUE INDEX ux_product_sku_sku_code ON product_sku (sku_code);
CREATE INDEX ix_product_sku_spu_id ON product_sku (spu_id);

CREATE TABLE sku_spec (
    id TEXT NOT NULL PRIMARY KEY,
    sku_id TEXT NOT NULL,
    spec_name TEXT NOT NULL,
    spec_value TEXT NOT NULL,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

CREATE UNIQUE INDEX ux_sku_spec_sku_id_name ON sku_spec (sku_id, spec_name);

CREATE TABLE inventory_balance (
    sku_id TEXT NOT NULL PRIMARY KEY,
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (typeof(quantity) = 'integer' AND quantity >= 0),
    average_cost NUMERIC NOT NULL CHECK (average_cost >= 0 AND average_cost = ROUND(average_cost, 4)),
    version INTEGER NOT NULL DEFAULT 0 CHECK (typeof(version) = 'integer' AND version >= 0),
    updated_at TEXT NOT NULL,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

CREATE TABLE inbound_order (
    id TEXT NOT NULL PRIMARY KEY,
    order_no TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    total_quantity INTEGER NOT NULL CHECK (typeof(total_quantity) = 'integer' AND total_quantity > 0),
    total_amount NUMERIC NOT NULL CHECK (total_amount >= 0 AND total_amount = ROUND(total_amount, 2)),
    remark TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_inbound_order_order_no ON inbound_order (order_no);

CREATE TABLE inbound_line (
    id TEXT NOT NULL PRIMARY KEY,
    inbound_order_id TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity > 0),
    unit_cost NUMERIC NOT NULL CHECK (unit_cost >= 0 AND unit_cost = ROUND(unit_cost, 2)),
    subtotal NUMERIC NOT NULL CHECK (subtotal >= 0 AND subtotal = ROUND(subtotal, 2)),
    FOREIGN KEY (inbound_order_id) REFERENCES inbound_order (id),
    FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

CREATE INDEX ix_inbound_line_inbound_order_id ON inbound_line (inbound_order_id);
CREATE INDEX ix_inbound_line_sku_id ON inbound_line (sku_id);

CREATE TABLE stock_movement (
    id TEXT NOT NULL PRIMARY KEY,
    movement_type TEXT NOT NULL,
    document_id TEXT NOT NULL,
    document_no TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta) = 'integer' AND quantity_delta <> 0),
    quantity_before INTEGER NOT NULL CHECK (typeof(quantity_before) = 'integer' AND quantity_before >= 0),
    quantity_after INTEGER NOT NULL CHECK (typeof(quantity_after) = 'integer' AND quantity_after >= 0),
    unit_cost NUMERIC NOT NULL CHECK (unit_cost >= 0 AND unit_cost = ROUND(unit_cost, 2)),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

CREATE INDEX ix_stock_movement_sku_id_occurred_at ON stock_movement (sku_id, occurred_at);
CREATE UNIQUE INDEX ux_stock_movement_source ON stock_movement (movement_type, document_id, sku_id);

CREATE TABLE idempotency_request (
    request_id TEXT NOT NULL PRIMARY KEY,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    created_at TEXT NOT NULL
);
