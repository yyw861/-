import axios from 'axios'; import { http } from '@/shared/api/http'; import type { ReturnReceipt, ReturnRequest } from './types'
export async function createReturn(request: ReturnRequest, requestId: string): Promise<ReturnReceipt> {
  return (await http.post<ReturnReceipt>('/returns', request, { headers: { 'Idempotency-Key': requestId } })).data
}
export function returnError(error: unknown): string {
  if (axios.isAxiosError<{detail?:string}>(error)) return error.response?.data?.detail ?? error.message
  return error instanceof Error ? error.message : '退货失败，请重试'
}
