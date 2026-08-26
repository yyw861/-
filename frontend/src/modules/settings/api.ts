import { http } from '@/shared/api/http'
import type { DocumentNumbering, DocumentNumberingUpdate, OperationLogPage, PaymentMethod,
  PaymentMethodCreate, PaymentMethodPatch, ReceiptSetting, ReceiptSettingUpdate,
  StoreSetting, StoreSettingUpdate, BackupItem, RestorePreview, RestoreResult } from './types'

export async function getStoreSetting() { return (await http.get<StoreSetting>('/settings/store')).data }
export async function updateStoreSetting(value: StoreSettingUpdate) { return (await http.put<StoreSetting>('/settings/store', value)).data }
export async function getReceiptSetting() { return (await http.get<ReceiptSetting>('/settings/receipt')).data }
export async function updateReceiptSetting(value: ReceiptSettingUpdate) { return (await http.put<ReceiptSetting>('/settings/receipt', value)).data }
export async function getDocumentNumbering() { return (await http.get<DocumentNumbering[]>('/settings/document-numbering')).data }
export async function updateDocumentNumbering(type: string, value: DocumentNumberingUpdate) {
  return (await http.put<DocumentNumbering>(`/settings/document-numbering/${encodeURIComponent(type)}`, value)).data
}
export async function getPaymentMethods() { return (await http.get<PaymentMethod[]>('/settings/payment-methods')).data }
export async function createPaymentMethod(value: PaymentMethodCreate) { return (await http.post<PaymentMethod>('/settings/payment-methods', value)).data }
export async function patchPaymentMethod(code: string, value: PaymentMethodPatch) {
  return (await http.patch<PaymentMethod>(`/settings/payment-methods/${encodeURIComponent(code)}`, value)).data
}
export async function getOperationLogs(operationType?: string, result?: string, page = 0, size = 20) {
  return (await http.get<OperationLogPage>('/operation-logs', { params: { operationType, result, page, size } })).data
}
export async function getBackups() { return (await http.get<BackupItem[]>('/backups')).data }
export async function createBackup() { return (await http.post<BackupItem>('/backups')).data }
export async function previewRestore(id: string) { return (await http.post<RestorePreview>(`/backups/${id}/restore-preview`)).data }
export async function restoreBackup(id: string, confirmationText: string) {
  return (await http.post<RestoreResult>(`/backups/${id}/restore`, { confirmationText })).data
}
