export interface ReturnLine { id: string; originalSaleLineId: string; skuId: string; skuCode: string; barcode: string; quantity: number; refundAmount: number; costUnitSnapshot: number }
export interface Refund { id: string; methodCode: string; amount: number; occurredAt: string }
export interface ReturnReceipt { id: string; orderNo: string; originalSaleOrderId: string; originalSaleOrderNo: string; occurredAt: string; refundAmount: number; refundMethodCode: string; reason: string | null; status: string; createdAt: string; lines: ReturnLine[]; refund: Refund }
export interface ReturnRequest { originalSaleOrderId: string; reason: string; refundMethodCode: string; lines: Array<{ originalSaleLineId: string; quantity: number }> }
