import { http } from '@/shared/api/http'
import type { CheckoutRequest, SalePage, SaleReceipt } from './types'

export async function checkoutSale(request: CheckoutRequest, requestId: string): Promise<SaleReceipt> {
  return (await http.post<SaleReceipt>('/sales', request, { headers: { 'Idempotency-Key': requestId } })).data
}
export async function getSales(params: Record<string, unknown> = {}): Promise<SalePage> {
  return (await http.get<SalePage>('/sales', { params })).data
}
export async function getSale(id: string): Promise<SaleReceipt> {
  return (await http.get<SaleReceipt>(`/sales/${id}`)).data
}
