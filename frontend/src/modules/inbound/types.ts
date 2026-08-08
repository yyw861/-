import type { Sku } from '../catalog/types'

export interface InboundLineRequest {
  skuId: string
  quantity: number
  unitCost: number
}

export interface ConfirmInboundRequest {
  remark: string
  lines: InboundLineRequest[]
}

export interface InboundLineView extends InboundLineRequest {
  id: string
  skuCode: string
  barcode: string
  productName: string
  subtotal: number
}

export interface InboundSummary {
  id: string
  orderNo: string
  occurredAt: string
  totalQuantity: number
  totalAmount: number
  remark: string | null
  status: string
  createdAt: string
}

export interface InboundReceipt extends InboundSummary {
  lines: InboundLineView[]
}

export interface InboundPage {
  items: InboundSummary[]
  total: number
  page: number
  size: number
}

export interface DraftLine {
  id: string
  sku: Sku
  productName: string
  quantity: number
  unitCost: number
}
