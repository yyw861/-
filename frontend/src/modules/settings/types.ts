export interface StoreSetting {
  storeName: string
  phone: string | null
  address: string | null
  deviceName: string
  updatedAt: string
}

export interface StoreSettingUpdate {
  storeName: string
  phone: string | null
  address: string | null
  deviceName: string
}

export interface ReceiptSetting {
  headerText: string | null
  footerText: string | null
  showPhone: boolean
  showAddress: boolean
  paperWidth: 58 | 80
  updatedAt: string
}

export type ReceiptSettingUpdate = Omit<ReceiptSetting, 'updatedAt'>

export interface DocumentNumbering {
  documentType: string
  prefix: string
  nextValue: number
  updatedAt: string
}

export interface DocumentNumberingUpdate { prefix: string; nextValue: number }

export interface PaymentMethod { code: string; name: string; enabled: boolean; sortOrder: number }
export interface PaymentMethodCreate { code: string; name: string; sortOrder: number }
export interface PaymentMethodPatch { name?: string; enabled?: boolean; sortOrder?: number }

export interface OperationLogItem {
  id: string
  operationType: string
  objectType: string
  objectId: string | null
  occurredAt: string
  result: 'SUCCESS' | 'FAILED'
  message: string | null
  deviceSummary: string | null
}

export interface OperationLogPage { items: OperationLogItem[]; total: number; page: number; size: number }

export interface BackupItem {
  id: string; fileName: string; filePath: string; sha256: string | null; fileSize: number
  backupType: 'MANUAL' | 'PRE_RESTORE'; status: 'STARTED' | 'SUCCEEDED' | 'FAILED'
  createdAt: string; completedAt: string | null; errorMessage: string | null
}
export interface RestorePreview {
  backupId: string; fileName: string; fileSize: number; sha256: string
  schemaVersion: string; compatible: boolean; message: string
}
export interface RestoreResult { backupId: string; protectionBackupId: string; restoredAt: string; status: string }
