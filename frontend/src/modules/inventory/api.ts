import { http } from '@/shared/api/http'

import type { InventoryPage, StockMovement } from './types'

export interface InventoryQuery {
  name?: string
  skuCode?: string
  barcode?: string
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
      page: query.page ?? 0,
      size: query.size ?? 50,
    },
  })).data
}

export async function getStockMovements(skuId: string): Promise<StockMovement[]> {
  return (await http.get<StockMovement[]>(`/inventory/${skuId}/movements`)).data
}
