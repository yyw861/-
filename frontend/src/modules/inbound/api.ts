import { http } from '@/shared/api/http'
import type { ConfirmInboundRequest, InboundPage, InboundReceipt } from './types'

export async function confirmInbound(request: ConfirmInboundRequest, requestId: string): Promise<InboundReceipt> {
  return (await http.post<InboundReceipt>('/inbounds', request, {
    headers: { 'Idempotency-Key': requestId },
  })).data
}

export interface InboundHistoryQuery {
  fromDate?: string
  toDate?: string
  orderNo?: string
  page?: number
  size?: number
}

export async function getInboundHistory(query: InboundHistoryQuery): Promise<InboundPage> {
  const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== '' && value !== undefined))
  return (await http.get<InboundPage>('/inbounds', { params })).data
}

export async function getInboundDetail(id: string): Promise<InboundReceipt> {
  return (await http.get<InboundReceipt>(`/inbounds/${id}`)).data
}
