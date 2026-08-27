import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReturnDialog from './ReturnDialog.vue'

const returnApi = vi.hoisted(() => ({ createReturn: vi.fn() }))
vi.mock('../api', () => returnApi)

const sale = { id: 'sale-1', orderNo: 'SO-001', occurredAt: '2026-08-05T01:00:00Z', originalAmount: 300,
  discountAmount: 30, actualAmount: 270, status: 'PARTIALLY_RETURNED', remark: null,
  createdAt: '2026-08-05T01:00:00Z', payments: [], lines: [
    { id: 'line-1', skuId: 'sku-1', skuCode: 'BALL-1', barcode: '69001', quantity: 2, listUnitPrice: 150,
      allocatedDiscount: 30, actualAmount: 270, costUnitSnapshot: 100, returnedQuantity: 1 },
    { id: 'line-2', skuId: 'sku-2', skuCode: 'SHOE-1', barcode: '69002', quantity: 1, listUnitPrice: 0,
      allocatedDiscount: 0, actualAmount: 0, costUnitSnapshot: 0, returnedQuantity: 1 },
  ] }

describe('ReturnDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('only offers remaining lines, caps quantity and previews refund', async () => {
    const wrapper = mount(ReturnDialog, { props: { open: true, sale } })
    expect(wrapper.text()).toContain('BALL-1')
    expect(wrapper.text()).not.toContain('SHOE-1')
    const input = wrapper.get('[data-testid="return-quantity-line-1"]')
    expect(input.attributes('max')).toBe('1')
    await input.setValue('1')
    expect(wrapper.get('[data-testid="refund-preview"]').text()).toContain('135.00')
  })

  it('submits a new idempotency key and emits success for parent refresh', async () => {
    returnApi.createReturn.mockResolvedValue({ id: 'return-1', orderNo: 'RT-001', refundAmount: 135, lines: [] })
    const wrapper = mount(ReturnDialog, { props: { open: true, sale } })
    await wrapper.get('[data-testid="return-quantity-line-1"]').setValue('1')
    await wrapper.get('[data-testid="confirm-return"]').trigger('click')
    await flushPromises()
    expect(returnApi.createReturn).toHaveBeenCalledWith(expect.objectContaining({
      originalSaleOrderId: sale.id, lines: [{ originalSaleLineId: 'line-1', quantity: 1 }],
    }), expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(wrapper.emitted('success')?.[0]?.[0]).toMatchObject({ orderNo: 'RT-001' })
  })
})
