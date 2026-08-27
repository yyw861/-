CREATE TABLE stock_movement_new (
    id TEXT NOT NULL PRIMARY KEY,
    movement_type TEXT NOT NULL,
    document_id TEXT NOT NULL,
    document_no TEXT NOT NULL,
    sku_id TEXT NOT NULL,
    quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta) = 'integer' AND quantity_delta <> 0),
    quantity_before INTEGER NOT NULL CHECK (typeof(quantity_before) = 'integer' AND quantity_before >= 0),
    quantity_after INTEGER NOT NULL CHECK (typeof(quantity_after) = 'integer' AND quantity_after >= 0),
    unit_cost NUMERIC NOT NULL CHECK (unit_cost >= 0 AND unit_cost = ROUND(unit_cost, 4)),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

INSERT INTO stock_movement_new
    (id, movement_type, document_id, document_no, sku_id, quantity_delta,
     quantity_before, quantity_after, unit_cost, occurred_at)
SELECT id, movement_type, document_id, document_no, sku_id, quantity_delta,
       quantity_before, quantity_after, unit_cost, occurred_at
  FROM stock_movement;

DROP TABLE stock_movement;
ALTER TABLE stock_movement_new RENAME TO stock_movement;

CREATE INDEX ix_stock_movement_sku_id_occurred_at ON stock_movement (sku_id, occurred_at);
CREATE UNIQUE INDEX ux_stock_movement_source ON stock_movement (movement_type, document_id, sku_id);
