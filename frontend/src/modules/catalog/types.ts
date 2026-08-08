export interface Category {
  id: string
  name: string
  sortOrder: number
  enabled: boolean
}

export interface Brand {
  id: string
  name: string
  remark: string | null
  enabled: boolean
}

export interface Sku {
  id: string
  spuId: string
  skuCode: string
  barcode: string
  specs: Record<string, string>
  retailPrice: number
  warningStock: number
  enabled: boolean
}

export interface Product {
  id: string
  name: string
  categoryId: string
  brandId: string
  imageUrl: string | null
  description: string | null
  enabled: boolean
  skus: Sku[]
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface QuickCreateSkuRequest {
  categoryId: string
  brandId: string
  existingSpuId: string | null
  productName: string
  skuCode: string
  barcode: string
  specs: Record<string, string>
  retailPrice: number
  warningStock: number
}

export interface ProductUpdateRequest {
  productName: string
  categoryId: string
  brandId: string
  imageUrl: string | null
  description: string | null
  enabled: boolean
  skus?: Array<SkuUpdateRequest & { skuId: string }>
}

export interface SkuUpdateRequest {
  skuCode: string
  barcode: string
  specs: Record<string, string>
  retailPrice: number
  warningStock: number
  enabled: boolean
}
