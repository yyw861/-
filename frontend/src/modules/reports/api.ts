import { http } from '@/shared/api/http'
import type { CategoryShare, InboundSummary, InventoryValuation, LowStockItem, ProductRanking, SalesSummary } from './types'

const rangeParams = (fromDate: string, toDate: string) => ({ fromDate, toDate })

export async function getSalesSummary(fromDate: string, toDate: string) {
  return (await http.get<SalesSummary>('/reports/sales', { params: rangeParams(fromDate, toDate) })).data
}
export async function getProductRanking(fromDate: string, toDate: string, limit = 100) {
  return (await http.get<ProductRanking[]>('/reports/products', { params: { ...rangeParams(fromDate, toDate), limit } })).data
}
export async function getCategoryShare(fromDate: string, toDate: string) {
  return (await http.get<CategoryShare[]>('/reports/categories', { params: rangeParams(fromDate, toDate) })).data
}
export async function getInboundSummary(fromDate: string, toDate: string) {
  return (await http.get<InboundSummary>('/reports/inbound', { params: rangeParams(fromDate, toDate) })).data
}
export async function getInventoryValuation() {
  return (await http.get<InventoryValuation>('/reports/inventory')).data
}
export async function getLowStock() {
  return (await http.get<LowStockItem[]>('/reports/low-stock')).data
}
