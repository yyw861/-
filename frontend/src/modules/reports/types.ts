import type { MoneyValue } from './currency'
export interface DateRange { fromDate: string; toDate: string }
export interface SalesTrendPoint { date: string; netSalesAmount: MoneyValue; grossProfit: MoneyValue }
export interface SalesSummary { grossSalesAmount: MoneyValue; refundAmount: MoneyValue; netSalesAmount: MoneyValue;
  grossProfit: MoneyValue; orderCount: number; netQuantity: number; trend: SalesTrendPoint[] }
export interface ProductRanking { skuId: string; skuCode: string; barcode: string; productName: string;
  grossQuantity: number; returnedQuantity: number; netQuantity: number; netSalesAmount: MoneyValue }
export interface CategoryShare { categoryId: string; categoryName: string; netSalesAmount: MoneyValue }
export interface InboundSummary { orderCount: number; totalQuantity: number; totalAmount: MoneyValue }
export interface InventoryValuation { skuCount: number; totalQuantity: number; totalCost: MoneyValue }
export interface LowStockItem { skuId: string; skuCode: string; barcode: string; productName: string;
  quantity: number; warningStock: number }
