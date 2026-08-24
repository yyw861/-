import { defineStore } from 'pinia'
import { checkoutSale } from '../api'
import type { CartLine, SaleReceipt } from '../types'

const cents = (value: number) => Math.round(value * 100)
export const useCartStore = defineStore('salesCart', {
  state: () => ({ lines: [] as CartLine[], discount: 0, remark: '', submitting: false,
    requestId: null as string | null, lastReceipt: null as SaleReceipt | null }),
  getters: {
    originalAmount: (state) => state.lines.reduce((sum, line) => sum + cents(line.unitPrice) * line.quantity, 0) / 100,
    actualAmount(): number { return Math.max(0, (cents(this.originalAmount) - cents(this.discount)) / 100) },
  },
  actions: {
    scan(line: Omit<CartLine, 'quantity'>) {
      const existing = this.lines.find((item) => item.skuId === line.skuId)
      if (existing) {
        if (existing.quantity >= existing.available) throw new Error(`库存不足，当前可售 ${existing.available} 件`)
        existing.quantity += 1
      } else {
        if (line.available < 1) throw new Error('库存不足，当前商品无可售库存')
        this.lines.push({ ...line, quantity: 1 })
      }
      this.changed()
    },
    changeQuantity(skuId: string, quantity: number) {
      const line = this.lines.find((item) => item.skuId === skuId)
      if (!line || !Number.isInteger(quantity) || quantity < 1) throw new Error('销售数量必须是正整数')
      if (quantity > line.available) throw new Error(`库存不足，当前可售 ${line.available} 件`)
      line.quantity = quantity; this.changed()
    },
    remove(skuId: string) { this.lines = this.lines.filter((line) => line.skuId !== skuId); this.changed() },
    setDiscount(value: number) {
      if (!Number.isFinite(value) || value < 0 || cents(value) > cents(this.originalAmount)) throw new Error('优惠金额不能超过商品原价')
      this.discount = cents(value) / 100; this.changed()
    },
    changed() { this.requestId = null },
    async checkout(methodCode: string, paymentAmount: number): Promise<SaleReceipt> {
      if (this.submitting) throw new Error('销售单正在提交')
      if (!this.lines.length) throw new Error('购物车为空')
      if (cents(paymentAmount) !== cents(this.actualAmount)) throw new Error('支付金额必须等于实收金额')
      this.submitting = true; this.requestId ??= crypto.randomUUID()
      try {
        const receipt = await checkoutSale({ discountAmount: this.discount, remark: this.remark,
          lines: this.lines.map(({ skuId, quantity }) => ({ skuId, quantity })),
          payments: [{ methodCode, amount: paymentAmount }] }, this.requestId)
        this.lines = []; this.discount = 0; this.remark = ''; this.requestId = null; this.lastReceipt = receipt
        return receipt
      } finally { this.submitting = false }
    },
    clear() { this.lines = []; this.discount = 0; this.remark = ''; this.requestId = null },
  },
})
