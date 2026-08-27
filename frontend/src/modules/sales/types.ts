export interface CartLine { skuId: string; productName: string; skuCode: string; barcode: string; unitPrice: number; quantity: number; available: number }
export interface SaleLine { id: string; skuId: string; skuCode: string; barcode: string; quantity: number; listUnitPrice: number; allocatedDiscount: number; actualAmount: number; costUnitSnapshot: number; returnedQuantity: number }
export interface Payment { id: string; methodCode: string; amount: number; occurredAt: string }
export interface SaleReceipt { id: string; orderNo: string; occurredAt: string; originalAmount: number; discountAmount: number; actualAmount: number; status: string; remark: string | null; createdAt: string; lines: SaleLine[]; payments: Payment[] }
export interface CheckoutRequest { discountAmount: number; remark: string; lines: Array<{ skuId: string; quantity: number }>; payments: Array<{ methodCode: string; amount: number }> }
export interface SaleSummary { id: string; orderNo: string; occurredAt: string; actualAmount: number; status: string }
export interface SalePage { items: SaleSummary[]; total: number; page: number; size: number }
