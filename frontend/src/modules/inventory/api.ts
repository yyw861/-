import { http } from '@/shared/api/http'

import type { AdjustmentLineInput, AdjustmentReceipt, InventoryPage, StockMovement } from './types'

export interface InventoryQuery {
  name?: string
  skuCode?: string
  barcode?: string
  lowStock?: boolean
  page?: number
  size?: number
}

export async function getInventory(query: InventoryQuery = {}): Promise<InventoryPage> {
  const name = query.name?.trim()
  const skuCode = query.skuCode?.trim()
  const barcode = query.barcode?.trim()
  return (await http.get<InventoryPage>('/inventory', {
    params: {
      ...(name ? { name } : {}),
      ...(skuCode ? { skuCode } : {}),
      ...(barcode ? { barcode } : {}),
      ...(query.lowStock ? { lowStock: true } : {}),
      page: query.page ?? 0,
      size: query.size ?? 50,
    },
  })).data
}

export async function createAdjustment(command: { lines: AdjustmentLineInput[] }, requestId: string) {
  return (await http.post<AdjustmentReceipt>('/inventory/adjustments', command, {
    headers: { 'Idempotency-Key': requestId },
  })).data
}

export async function getStockMovements(skuId: string): Promise<StockMovement[]> {
  return (await http.get<StockMovement[]>(`/inventory/${skuId}/movements`)).data
}
