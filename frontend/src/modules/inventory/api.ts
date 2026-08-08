import { http } from '@/shared/api/http'

import type { InventoryPage, StockMovement } from './types'

export async function getInventory(query: { keyword?: string; page?: number; size?: number } = {}): Promise<InventoryPage> {
  const keyword = query.keyword?.trim()
  return (await http.get<InventoryPage>('/inventory', {
    params: {
      ...(keyword ? { name: keyword } : {}),
      page: query.page ?? 0,
      size: query.size ?? 50,
    },
  })).data
}

export async function getStockMovements(skuId: string): Promise<StockMovement[]> {
  return (await http.get<StockMovement[]>(`/inventory/${skuId}/movements`)).data
}
