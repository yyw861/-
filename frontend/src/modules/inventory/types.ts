export interface InventoryItem {
  skuId: string
  productId: string
  productName: string
  categoryId: string
  categoryName: string
  brandId: string
  brandName: string
  skuCode: string
  barcode: string
  retailPrice: number
  warningStock: number
  enabled: boolean
  quantity: number
  averageCost: number
  inventoryValue: number
  version: number
  updatedAt: string
}

export interface InventoryPage {
  items: InventoryItem[]
  total: number
  page: number
  size: number
}

export interface StockMovement {
  id: string
  movementType: string
  documentId: string
  documentNo: string
  skuId: string
  quantityDelta: number
  quantityBefore: number
  quantityAfter: number
  unitCost: number
  occurredAt: string
}
