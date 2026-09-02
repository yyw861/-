import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AdjustmentDialog from './AdjustmentDialog.vue'

const inventoryApi = vi.hoisted(() => ({ createAdjustment: vi.fn() }))
vi.mock('../api', () => inventoryApi)
vi.mock('../../catalog/api', () => ({
  errorMessage: vi.fn((error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'),
}))

const item = {
  skuId: 'sku-1', productId: 'spu-1', productName: '训练篮球', categoryId: 'category-1',
  categoryCode: '69', categoryName: '球类用品', subCategoryId: 'sub-1', subCategoryCode: '01', subCategoryName: '篮球', brandId: 'brand-1', brandName: '测试品牌', skuCode: 'BALL-01',
  barcode: '6900000000012', retailPrice: 159, warningStock: 3, enabled: true, quantity: 10,
  averageCost: 100, inventoryValue: 1000, version: 1, updatedAt: '2026-08-24T08:00:00Z',
}

describe('AdjustmentDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the signed difference and requires a changed integer count with a reason', async () => {
    const wrapper = mount(AdjustmentDialog, { props: { open: true, item } })
    const submit = wrapper.get('[data-testid="confirm-adjustment"]')

    expect(wrapper.text()).toContain('系统库存 10')
    expect(submit.attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-testid="counted-quantity"]').setValue('13')
    expect(wrapper.get('[data-testid="adjustment-difference"]').text()).toContain('+3')
    expect(submit.attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-testid="adjustment-reason"]').setValue('盘盈复核')
    expect(submit.attributes()).not.toHaveProperty('disabled')
    await wrapper.get('[data-testid="counted-quantity"]').setValue('10.5')
    expect(submit.attributes()).toHaveProperty('disabled')
  })

  it('submits the system snapshot and counted quantity with an idempotency key', async () => {
    inventoryApi.createAdjustment.mockResolvedValue({ id: 'adjustment-1', orderNo: 'AD-001', lines: [] })
    const wrapper = mount(AdjustmentDialog, { props: { open: true, item } })
    await wrapper.get('[data-testid="counted-quantity"]').setValue('7')
    await wrapper.get('[data-testid="adjustment-reason"]').setValue('破损报废')
    await wrapper.get('[data-testid="confirm-adjustment"]').trigger('click')
    await flushPromises()

    expect(inventoryApi.createAdjustment).toHaveBeenCalledWith({ lines: [{
      skuId: 'sku-1', systemQuantity: 10, countedQuantity: 7, reason: '破损报废',
    }] }, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(wrapper.emitted('success')?.[0]?.[0]).toMatchObject({ orderNo: 'AD-001' })
  })

  it('reuses the idempotency key when an uncertain request is retried', async () => {
    inventoryApi.createAdjustment.mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({
      id: 'adjustment-1', orderNo: 'AD-001', lines: [],
    })
    const wrapper = mount(AdjustmentDialog, { props: { open: true, item } })
    await wrapper.get('[data-testid="counted-quantity"]').setValue('12')
    await wrapper.get('[data-testid="adjustment-reason"]').setValue('盘盈')
    await wrapper.get('[data-testid="confirm-adjustment"]').trigger('click')
    await flushPromises()
    const firstKey = inventoryApi.createAdjustment.mock.calls[0][1]

    await wrapper.get('[data-testid="confirm-adjustment"]').trigger('click')
    await flushPromises()

    expect(inventoryApi.createAdjustment.mock.calls[1][1]).toBe(firstKey)
  })
})
