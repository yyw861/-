import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import InventoryView from './InventoryView.vue'

const inventoryApi = vi.hoisted(() => ({
  getInventory: vi.fn(),
  getStockMovements: vi.fn(),
  createAdjustment: vi.fn(),
}))
const exportTools = vi.hoisted(() => ({ downloadInventoryCsv: vi.fn() }))

vi.mock('../api', () => inventoryApi)
vi.mock('../exportInventoryCsv', () => exportTools)
vi.mock('../../catalog/api', () => ({
  errorMessage: vi.fn((error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'),
}))

describe('InventoryView', () => {
  beforeEach(() => {
    exportTools.downloadInventoryCsv.mockReset()
    inventoryApi.getInventory.mockReset().mockResolvedValue({
      items: [{
        skuId: 'sku-1', productId: 'spu-1', productName: '训练篮球', categoryId: 'category-1',
        categoryCode: '69', categoryName: '球类用品', subCategoryId: 'sub-1', subCategoryCode: '01', subCategoryName: '篮球', brandId: 'brand-1', brandName: '测试品牌', skuCode: 'BALL-01',
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

  it('can reach the fifty-first inventory row and open its movements', async () => {
    inventoryApi.getInventory.mockImplementation(({ page = 0 }: { page?: number }) => Promise.resolve({
      items: [{
        skuId: page === 0 ? 'sku-1' : 'sku-51', productId: 'spu-1',
        productName: page === 0 ? '第一页商品' : '第51件商品', categoryId: 'category-1',
        categoryCode: '69', categoryName: '球类用品', subCategoryId: 'sub-1', subCategoryCode: '01', subCategoryName: '篮球', brandId: 'brand-1', brandName: '测试品牌',
        skuCode: page === 0 ? 'BALL-01' : 'BALL-51', barcode: page === 0 ? '6900000000012' : '6900000000051',
        retailPrice: 159, warningStock: 0, enabled: true, quantity: 10,
        averageCost: 100, inventoryValue: 1000, version: 1, updatedAt: '2026-08-04T16:30:00Z',
      }],
      total: 51, page, size: 50,
    }))
    const wrapper = mount(InventoryView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('共 51 条')
    await wrapper.get('[data-testid="inventory-next-page"]').trigger('click')
    await flushPromises()

    expect(inventoryApi.getInventory).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }))
    expect(wrapper.text()).toContain('第51件商品')
    await wrapper.get('button.link').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('BALL-51')
    wrapper.unmount()
  })

  it('offers name, SKU code, and barcode searches and resets to the first page', async () => {
    const wrapper = mount(InventoryView)
    await flushPromises()

    expect(wrapper.get('[data-testid="inventory-search-field"]').text()).toContain('商品名称')
    expect(wrapper.get('[data-testid="inventory-search-field"]').text()).toContain('SKU 编码')
    expect(wrapper.get('[data-testid="inventory-search-field"]').text()).toContain('条码')
    await wrapper.get('[data-testid="inventory-search-field"]').setValue('skuCode')
    await wrapper.get('[data-testid="inventory-search-value"]').setValue('BALL-51')
    await wrapper.get('form.search').trigger('submit')
    await flushPromises()

    expect(inventoryApi.getInventory).toHaveBeenLastCalledWith({ skuCode: 'BALL-51', page: 0, size: 50 })
  })

  it('switches to low-stock results and opens an adjustment for the selected SKU', async () => {
    const wrapper = mount(InventoryView)
    await flushPromises()

    await wrapper.get('[data-testid="low-stock-only"]').setValue(true)
    await flushPromises()
    expect(inventoryApi.getInventory).toHaveBeenLastCalledWith(expect.objectContaining({ lowStock: true }))

    await wrapper.get('[data-testid="open-adjustment-sku-1"]').trigger('click')
    expect(wrapper.get('[aria-labelledby="adjustment-title"]').text()).toContain('BALL-01')
  })

  it('exports every page from the current filter', async () => {
    inventoryApi.getInventory.mockImplementation(({ page = 0, size = 50 }: { page?: number; size?: number }) => {
      const count = size === 100 ? (page === 0 ? 100 : 1) : 1
      return Promise.resolve({ items: Array.from({ length: count }, (_, index) => ({
        skuId: `sku-${page}-${index}`, productId: 'spu-1', productName: '训练篮球', categoryId: 'category-1',
        categoryCode: '69', categoryName: '球类用品', subCategoryId: 'sub-1', subCategoryCode: '01', subCategoryName: '篮球', brandId: 'brand-1', brandName: '测试品牌', skuCode: `BALL-${index}`,
        barcode: `6900${index}`, retailPrice: 159, warningStock: 3, enabled: true, quantity: 2,
        averageCost: 100, inventoryValue: 200, version: 1, updatedAt: '2026-08-24T08:00:00Z',
      })), total: 101, page, size })
    })
    const wrapper = mount(InventoryView)
    await flushPromises()
    await wrapper.get('[data-testid="inventory-search-value"]').setValue('篮球')
    await wrapper.get('form.search').trigger('submit')
    await flushPromises()

    await wrapper.get('button.secondary').trigger('click')
    await flushPromises()

    expect(inventoryApi.getInventory).toHaveBeenCalledWith({ name: '篮球', page: 0, size: 100 })
    expect(inventoryApi.getInventory).toHaveBeenCalledWith({ name: '篮球', page: 1, size: 100 })
    expect(exportTools.downloadInventoryCsv).toHaveBeenCalledWith(expect.arrayContaining([
      expect.objectContaining({ skuId: 'sku-1-0' }),
    ]))
    expect(exportTools.downloadInventoryCsv.mock.calls[0][0]).toHaveLength(101)
  })
})
