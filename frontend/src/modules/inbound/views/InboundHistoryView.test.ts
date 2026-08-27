import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import InboundHistoryView from './InboundHistoryView.vue'

const api = vi.hoisted(() => ({ getInboundHistory: vi.fn(), getInboundDetail: vi.fn() }))
vi.mock('../api', () => api)

describe('InboundHistoryView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    api.getInboundHistory.mockResolvedValue({
      items: [{ id: 'in-1', orderNo: 'IN-20260805-000001', occurredAt: '2026-08-05T01:00:00Z', totalQuantity: 2, totalAmount: 160, remark: '', status: 'CONFIRMED', createdAt: '2026-08-05T01:00:00Z' }],
      total: 1, page: 0, size: 20,
    })
    api.getInboundDetail.mockResolvedValue({
      id: 'in-1', orderNo: 'IN-20260805-000001', occurredAt: '2026-08-05T01:00:00Z', totalQuantity: 2, totalAmount: 160, remark: '', status: 'CONFIRMED', createdAt: '2026-08-05T01:00:00Z',
      lines: [{ id: 'line-1', skuId: 'sku-1', skuCode: 'BALL-01', barcode: '6900000000012', productName: '训练篮球', quantity: 2, unitCost: 80, subtotal: 160 }],
    })
  })

  it('filters by local dates and order number and opens immutable receipt details', async () => {
    const wrapper = mount(InboundHistoryView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.get('[data-testid="from-date"]').setValue('2026-08-01')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-05')
    await wrapper.get('[data-testid="order-no"]').setValue('000001')
    await wrapper.get('[data-testid="history-search"]').trigger('click')
    await flushPromises()

    expect(api.getInboundHistory).toHaveBeenLastCalledWith(expect.objectContaining({ fromDate: '2026-08-01', toDate: '2026-08-05', orderNo: '000001' }))
    await wrapper.get('[data-testid="detail-in-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="inbound-detail"]').text()).toContain('训练篮球')
    expect(wrapper.get('[data-testid="inbound-detail"]').text()).toContain('BALL-01')
  })

  it('rejects a reversed local date range without sending a doomed request', async () => {
    const wrapper = mount(InboundHistoryView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(api.getInboundHistory).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="from-date"]').setValue('2026-08-06')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-05')
    await wrapper.get('[data-testid="history-search"]').trigger('click')
    await flushPromises()

    expect(api.getInboundHistory).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('开始日期不能晚于结束日期')
  })

  it('moves focus into receipt details and closes them with Escape', async () => {
    const wrapper = mount(InboundHistoryView, { attachTo: document.body, global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.get('[data-testid="detail-in-1"]').trigger('click')
    await flushPromises()

    const close = wrapper.get('[data-testid="inbound-detail-close"]')
    const trigger = wrapper.get('[data-testid="detail-in-1"]')
    expect(document.activeElement).toBe(close.element)
    await wrapper.get('[data-testid="inbound-detail"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="inbound-detail"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
  })
})
