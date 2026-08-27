import { http } from '@/shared/api/http'
import type { ProductRanking } from '../reports/types'
import type { MoneyValue } from '../reports/currency'

export interface RecentDocument { documentType: 'SALE' | 'INBOUND'; id: string; orderNo: string; occurredAt: string; amount: MoneyValue }
export interface DashboardView { date:string; salesAmount:MoneyValue; salesOrderCount:number; grossProfit:MoneyValue;
  inboundAmount:MoneyValue; inboundQuantity:number; inventoryQuantity:number; inventoryValue:MoneyValue;
  lowStockCount:number; topProducts:ProductRanking[]; recentDocuments:RecentDocument[] }

export async function getDashboard(date?: string) {
  return (await http.get<DashboardView>('/dashboard', { params: date ? { date } : {} })).data
}
