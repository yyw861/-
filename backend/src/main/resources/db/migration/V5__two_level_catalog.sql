PRAGMA defer_foreign_keys = ON;

DELETE FROM refund_record;
DELETE FROM return_line;
DELETE FROM return_order;
DELETE FROM payment_record;
DELETE FROM sale_line;
DELETE FROM sale_order;
DELETE FROM stock_adjustment_line;
DELETE FROM stock_adjustment_order;
DELETE FROM stock_movement;
DELETE FROM inbound_line;
DELETE FROM inbound_order;
DELETE FROM inventory_balance;
DELETE FROM sku_spec;
DELETE FROM product_sku;
DELETE FROM product_spu;
DELETE FROM category;
DELETE FROM brand;
DELETE FROM idempotency_request;
DELETE FROM operation_log;
DELETE FROM backup_record;

DROP TABLE product_sku;
DROP TABLE product_spu;
DROP TABLE category;

CREATE TABLE category (
    id TEXT NOT NULL PRIMARY KEY,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (typeof(sort_order) = 'integer'),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (length(code) = 2 AND code NOT GLOB '*[^0-9]*')
);

CREATE UNIQUE INDEX ux_category_code ON category (code);
CREATE UNIQUE INDEX ux_category_name ON category (name);

CREATE TABLE sub_category (
    id TEXT NOT NULL PRIMARY KEY,
    category_id TEXT NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (typeof(sort_order) = 'integer'),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CHECK (length(code) = 2 AND code NOT GLOB '*[^0-9]*')
);

CREATE UNIQUE INDEX ux_sub_category_parent_code ON sub_category (category_id, code);
CREATE UNIQUE INDEX ux_sub_category_parent_name ON sub_category (category_id, name);
CREATE INDEX ix_sub_category_category_id ON sub_category (category_id);

CREATE TABLE product_spu (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    sub_category_id TEXT NOT NULL,
    brand_id TEXT NOT NULL,
    image_url TEXT,
    description TEXT,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (sub_category_id) REFERENCES sub_category (id) ON DELETE RESTRICT,
    FOREIGN KEY (brand_id) REFERENCES brand (id) ON DELETE RESTRICT
);

CREATE INDEX ix_product_spu_sub_category_id ON product_spu (sub_category_id);
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
    FOREIGN KEY (spu_id) REFERENCES product_spu (id) ON DELETE RESTRICT,
    CHECK (length(barcode) >= 3 AND barcode NOT GLOB '*[^0-9]*')
);

CREATE UNIQUE INDEX ux_product_sku_barcode ON product_sku (barcode);
CREATE UNIQUE INDEX ux_product_sku_sku_code ON product_sku (sku_code);
CREATE INDEX ix_product_sku_spu_id ON product_sku (spu_id);
