import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import CheckoutView from './CheckoutView.vue'
import { useCartStore } from '../stores/cart'

const catalogApi = vi.hoisted(() => ({
  findSkuByBarcode: vi.fn(), getProduct: vi.fn(),
  isNotFound: vi.fn((error: unknown) => (error as { response?: { status?: number } })?.response?.status === 404),
  errorMessage: vi.fn((error: unknown) => error instanceof Error ? error.message : '操作失败'),
}))
const inventoryApi = vi.hoisted(() => ({ getInventory: vi.fn() }))
const salesApi = vi.hoisted(() => ({ checkoutSale: vi.fn() }))
vi.mock('../../catalog/api', () => catalogApi)
vi.mock('../../inventory/api', () => inventoryApi)
vi.mock('../api', () => salesApi)

const sku = { id: 'sku-1', spuId: 'spu-1', skuCode: 'BALL-01', barcode: '69001', specs: {}, retailPrice: 150,
  warningStock: 2, enabled: true }
const product = { id: 'spu-1', name: '训练篮球', categoryId: 'cat-1', brandId: 'brand-1', imageUrl: null,
  description: null, enabled: true, skus: [sku] }
const inventory = { skuId: sku.id, productId: product.id, productName: product.name, categoryId: 'cat-1',
  categoryName: '球类', brandId: 'brand-1', brandName: '品牌', skuCode: sku.skuCode, barcode: sku.barcode,
  retailPrice: 150, warningStock: 2, enabled: true, quantity: 2, averageCost: 100, inventoryValue: 200,
  version: 1, updatedAt: '2026-08-05T00:00:00Z' }

function mountView() {
  const pinia = createPinia(); setActivePinia(pinia)
  return mount(CheckoutView, { attachTo: document.body, global: { plugins: [pinia],
    stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
}

async function scan(wrapper: ReturnType<typeof mountView>, barcode = sku.barcode) {
  await wrapper.get('[data-testid="barcode-input"]').setValue(barcode)
  await wrapper.get('[data-testid="scan-form"]').trigger('submit')
  await flushPromises()
}

describe('CheckoutView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''; vi.clearAllMocks()
    catalogApi.findSkuByBarcode.mockResolvedValue(sku)
    catalogApi.getProduct.mockResolvedValue(product)
    inventoryApi.getInventory.mockResolvedValue({ items: [inventory], total: 1, page: 0, size: 10 })
  })

  it('adds by barcode and accumulates repeated scans', async () => {
    const wrapper = mountView(); await scan(wrapper); await scan(wrapper)
    expect(useCartStore().lines).toHaveLength(1)
    expect(useCartStore().lines[0]?.quantity).toBe(2)
    expect(wrapper.text()).toContain('训练篮球')
  })

  it('shows unknown and disabled barcode errors without adding', async () => {
    const wrapper = mountView()
    catalogApi.findSkuByBarcode.mockRejectedValueOnce({ response: { status: 404 } })
    await scan(wrapper, 'unknown')
    expect(wrapper.get('[role="alert"]').text()).toContain('未找到')
    catalogApi.findSkuByBarcode.mockResolvedValueOnce({ ...sku, enabled: false })
    await scan(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toContain('停用')
    expect(useCartStore().lines).toHaveLength(0)
  })

  it('prevents quantities above available inventory', async () => {
    const wrapper = mountView(); await scan(wrapper); await scan(wrapper); await scan(wrapper)
    expect(useCartStore().lines[0]?.quantity).toBe(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('库存不足')
  })

  it('updates checkout totals immediately while discount is typed', async () => {
    const wrapper = mountView(); await scan(wrapper); await scan(wrapper)
    const discount = wrapper.get('[data-testid="discount"]')
    ;(discount.element as HTMLInputElement).value = '30'
    await discount.trigger('input')
    expect(useCartStore().actualAmount).toBe(270)
    expect(wrapper.text()).toContain('¥270.00')
  })

  it('requires exact payment and prevents duplicate submission', async () => {
    let resolve!: (value: unknown) => void
    salesApi.checkoutSale.mockReturnValue(new Promise((done) => { resolve = done }))
    const wrapper = mountView(); await scan(wrapper)
    await wrapper.get('[data-testid="open-payment"]').trigger('click')
    await wrapper.get('[data-testid="payment-amount"]').setValue('149')
    expect(wrapper.get('[data-testid="confirm-payment"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="payment-amount"]').setValue('150')
    await Promise.all([wrapper.get('[data-testid="confirm-payment"]').trigger('click'),
      wrapper.get('[data-testid="confirm-payment"]').trigger('click')])
    expect(salesApi.checkoutSale).toHaveBeenCalledTimes(1)
    resolve({ id: 'sale-1', orderNo: 'SO-20260805-000001', occurredAt: '2026-08-05T01:00:00Z',
      originalAmount: 150, discountAmount: 0, actualAmount: 150, status: 'CONFIRMED', remark: null,
      createdAt: '2026-08-05T01:00:00Z', lines: [], payments: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('SO-20260805-000001')
    expect(useCartStore().lines).toHaveLength(0)
  })
})
