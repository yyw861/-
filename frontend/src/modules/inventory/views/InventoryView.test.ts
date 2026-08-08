import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import InventoryView from './InventoryView.vue'

const inventoryApi = vi.hoisted(() => ({
  getInventory: vi.fn(),
  getStockMovements: vi.fn(),
}))

vi.mock('../api', () => inventoryApi)
vi.mock('../../catalog/api', () => ({
  errorMessage: vi.fn((error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'),
}))

describe('InventoryView', () => {
  beforeEach(() => {
    inventoryApi.getInventory.mockReset().mockResolvedValue({
      items: [{
        skuId: 'sku-1', productId: 'spu-1', productName: '训练篮球', categoryId: 'category-1',
        categoryName: '球类用品', brandId: 'brand-1', brandName: '测试品牌', skuCode: 'BALL-01',
        barcode: '6900000000012', retailPrice: 159, warningStock: 0, enabled: true, quantity: 10,
        averageCost: 100, inventoryValue: 1000, version: 1, updatedAt: '2026-08-04T16:30:00Z',
      }],
      total: 1, page: 0, size: 50,
    })
    inventoryApi.getStockMovements.mockReset().mockResolvedValue([{
      id: 'movement-1', movementType: 'INBOUND', documentId: 'inbound-1', documentNo: 'IN-20260805-000001',
      skuId: 'sku-1', quantityDelta: 10, quantityBefore: 0, quantityAfter: 10, unitCost: 100,
      occurredAt: '2026-08-04T16:30:00Z',
    }])
  })

  it('shows stock movement time in the Asia/Shanghai business timezone', async () => {
    const wrapper = mount(InventoryView, { attachTo: document.body })
    await flushPromises()

    await wrapper.get('button.link').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain('2026/08/05 00:30')
    wrapper.unmount()
  })
})
