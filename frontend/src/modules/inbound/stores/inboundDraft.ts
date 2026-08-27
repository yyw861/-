import { defineStore } from 'pinia'

import { confirmInbound } from '../api'
import type { DraftLine, InboundReceipt } from '../types'
import type { Sku } from '../../catalog/types'

const MAX_JAVA_INTEGER = 2_147_483_647

interface AddLineInput {
  sku: Sku
  productName: string
  quantity: number
  unitCost: number
}

type AddLineResult = { kind: 'added' | 'merged' } | { kind: 'price-conflict' }

interface PendingConflict {
  existingIndex: number
  input: AddLineInput
}

export const useInboundDraftStore = defineStore('inboundDraft', {
  state: () => ({
    selectedCategoryId: '' as string,
    lines: [] as DraftLine[],
    remark: '',
    submitting: false,
    requestId: null as string | null,
    lastReceipt: null as InboundReceipt | null,
    pendingConflict: null as PendingConflict | null,
  }),
  actions: {
    selectCategory(id: string) {
      this.selectedCategoryId = id
    },
    addLine(input: AddLineInput): AddLineResult {
      if (!Number.isInteger(input.quantity) || input.quantity <= 0) throw new Error('入库数量必须是大于 0 的整数')
      if (input.quantity > MAX_JAVA_INTEGER) throw new Error('入库数量不能超过 2147483647')
      if (!Number.isFinite(input.unitCost) || input.unitCost < 0) throw new Error('进价必须是大于等于 0 的数字')
      const cents = input.unitCost * 100
      if (Math.abs(cents - Math.round(cents)) > Number.EPSILON * Math.max(1, Math.abs(cents)) * 4) {
        throw new Error('进价最多保留 2 位小数')
      }
      const sameSku = this.lines.findIndex((line) => line.sku.id === input.sku.id)
      if (sameSku >= 0) {
        if (this.lines[sameSku]!.unitCost === input.unitCost) {
          if (this.lines[sameSku]!.quantity > MAX_JAVA_INTEGER - input.quantity) {
            throw new Error('合并后的入库数量不能超过 2147483647')
          }
          this.lines[sameSku]!.quantity += input.quantity
          this.markChanged()
          return { kind: 'merged' }
        }
        this.pendingConflict = { existingIndex: sameSku, input }
        return { kind: 'price-conflict' }
      }
      this.lines.push(this.toLine(input))
      this.markChanged()
      return { kind: 'added' }
    },
    resolvePriceConflict(choice: 'update' | 'cancel') {
      const conflict = this.pendingConflict
      if (!conflict) return
      if (choice === 'update') {
        const line = this.lines[conflict.existingIndex]
        if (line) {
          if (line.quantity > MAX_JAVA_INTEGER - conflict.input.quantity) {
            throw new Error('合并后的入库数量不能超过 2147483647')
          }
          line.unitCost = conflict.input.unitCost
          line.quantity += conflict.input.quantity
        }
      }
      this.pendingConflict = null
      if (choice === 'update') this.markChanged()
    },
    removeLine(id: string) {
      this.lines = this.lines.filter((line) => line.id !== id)
      this.markChanged()
    },
    async confirm(): Promise<InboundReceipt> {
      if (this.submitting) throw new Error('入库单正在提交')
      if (this.lines.length === 0) throw new Error('请先添加入库商品')
      if (this.remark.length > 500) throw new Error('入库备注不能超过 500 个字符')
      let totalQuantity = 0
      for (const line of this.lines) {
        if (totalQuantity > MAX_JAVA_INTEGER - line.quantity) {
          throw new Error('整单入库总数量不能超过 2147483647')
        }
        totalQuantity += line.quantity
      }
      this.submitting = true
      this.requestId ??= crypto.randomUUID()
      try {
        const receipt = await confirmInbound({
          remark: this.remark,
          lines: this.lines.map((line) => ({ skuId: line.sku.id, quantity: line.quantity, unitCost: line.unitCost })),
        }, this.requestId)
        this.lines = []
        this.remark = ''
        this.requestId = null
        this.lastReceipt = receipt
        return receipt
      } finally {
        this.submitting = false
      }
    },
    markChanged() {
      this.requestId = null
    },
    toLine(input: AddLineInput): DraftLine {
      return { id: crypto.randomUUID(), ...input }
    },
  },
})
