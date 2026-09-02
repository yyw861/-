import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import InboundView from './InboundView.vue'
import BarcodeInput from '../components/BarcodeInput.vue'
import { useInboundDraftStore } from '../stores/inboundDraft'

const catalogApi = vi.hoisted(() => ({
  getCategories: vi.fn(),
  getSubCategories: vi.fn(),
  findCategoryByPrefix: vi.fn(),
  getBrands: vi.fn(),
  getProducts: vi.fn(),
  findSkuByBarcode: vi.fn(),
  getProduct: vi.fn(),
  quickCreateSku: vi.fn(),
  isNotFound: vi.fn((error: unknown) => (error as { response?: { status?: number } })?.response?.status === 404),
  errorMessage: vi.fn((error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'),
}))

const inboundApi = vi.hoisted(() => ({ confirmInbound: vi.fn() }))

vi.mock('../../catalog/api', () => catalogApi)
vi.mock('../api', () => inboundApi)

const categoryA = { id: 'cat-a', code: '69', name: '球类', sortOrder: 1, enabled: true }
const categoryB = { id: 'cat-b', code: '68', name: '服装', sortOrder: 2, enabled: true }
const subCategoryA = { id: 'sub-a', categoryId: categoryA.id, code: '01', name: '篮球', sortOrder: 1, enabled: true }
const sku = {
  id: 'sku-1',
  spuId: 'spu-1',
  skuCode: 'BALL-01',
  barcode: '6900000000012',
  specs: { 颜色: '红色' },
  retailPrice: 129,
  warningStock: 3,
  enabled: true,
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(InboundView, { attachTo: document.body, global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
}

describe('InboundView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    catalogApi.getCategories.mockResolvedValue([categoryA, categoryB])
    catalogApi.getSubCategories.mockResolvedValue([subCategoryA])
    catalogApi.findCategoryByPrefix.mockResolvedValue(categoryA)
    catalogApi.getBrands.mockResolvedValue([{ id: 'brand-1', name: '飞跃', remark: null, enabled: true }])
    catalogApi.getProducts.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
    catalogApi.getProduct.mockResolvedValue({
      id: 'spu-1', name: '训练篮球', categoryId: categoryA.id, subCategoryId: subCategoryA.id, brandId: 'brand-1',
      imageUrl: null, description: null, enabled: true, skus: [sku],
    })
  })

  it('allows scanning before any manual category selection', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-testid="barcode-input"]').attributes('disabled')).toBeUndefined()
  })

  it('locks scanning while a known SKU is pending, moves quantity to cost, then restores scanning after adding', async () => {
    catalogApi.findSkuByBarcode.mockResolvedValue(sku)
    const wrapper = mountView()
    await flushPromises()
    const barcode = wrapper.get('[data-testid="barcode-input"]')
    await barcode.setValue(sku.barcode)
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    expect((barcode.element as HTMLInputElement).value).toBe('')
    expect(document.activeElement).toBe(wrapper.get('[data-testid="pending-quantity"]').element)
    expect(barcode.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('训练篮球')

    wrapper.getComponent(BarcodeInput).vm.$emit('submit', 'SECOND-BARCODE')
    await flushPromises()
    expect(catalogApi.findSkuByBarcode).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-testid="pending-quantity"]').setValue('2')
    await wrapper.get('[data-testid="pending-quantity"]').trigger('keydown', { key: 'Enter' })
    expect(document.activeElement).toBe(wrapper.get('[data-testid="pending-unit-cost"]').element)
    await wrapper.get('[data-testid="pending-unit-cost"]').setValue('80')
    await wrapper.get('[data-testid="add-line"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="draft-table"]').text()).toContain('BALL-01')
    expect(wrapper.get('[data-testid="draft-table"]').text()).toContain('2')
    expect(wrapper.find('[data-testid="pending-sku"]').exists()).toBe(false)
    expect(document.activeElement).toBe(barcode.element)
  })

  it('opens quick create for an unknown barcode with its recognized major and minor choices', async () => {
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678901')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="quick-create-dialog"]').text()).toContain('快速建档')
    expect(catalogApi.findCategoryByPrefix).toHaveBeenCalledWith('69')
    expect(wrapper.get('[data-testid="quick-major-category"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="quick-major-category"]').text()).toContain('')
    expect((wrapper.get('[data-testid="quick-major-category"]').element as HTMLInputElement).value).toContain('69 球类')
    expect(wrapper.findAll('[data-testid="quick-sub-category-option"]')).toHaveLength(1)
    expect((wrapper.get('[data-testid="quick-barcode"]').element as HTMLInputElement).value).toBe('6912345678901')
    expect(wrapper.get('[data-testid="barcode-input"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    await vi.waitFor(() => {
      expect(document.activeElement).toBe(wrapper.get('[data-testid="quick-product-name"]').element)
    })

    await wrapper.get('[data-testid="quick-close"]').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="barcode-input"]').element)
  })

  it('submits an unknown barcode against its selected minor category', async () => {
    const newSku = { ...sku, id: 'sku-snapshot', spuId: 'spu-snapshot', barcode: '6912345678902' }
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    catalogApi.quickCreateSku.mockResolvedValue(newSku)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678902')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-testid="quick-product-name"]').setValue('快照篮球')
    await wrapper.get('[data-testid="quick-sku-code"]').setValue('SNAP-1')
    await wrapper.get('[data-testid="quick-retail-price"]').setValue('88')
    await wrapper.get('[data-testid="quick-save"]').trigger('click')
    await flushPromises()

    expect(catalogApi.quickCreateSku).toHaveBeenCalledWith(expect.objectContaining({
      subCategoryId: subCategoryA.id, barcode: '6912345678902', productName: '快照篮球',
    }))
  })

  it('backfills a newly created SKU but still requires quantity and cost before adding stock', async () => {
    const newSku = { ...sku, id: 'sku-new', spuId: 'spu-new', skuCode: 'NEW-01', barcode: '6912345678903' }
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    catalogApi.quickCreateSku.mockResolvedValue(newSku)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678903')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-testid="quick-product-name"]').setValue('新款篮球')
    await wrapper.get('[data-testid="quick-sku-code"]').setValue('NEW-01')
    await wrapper.get('[data-testid="quick-retail-price"]').setValue('99')
    await wrapper.get('[data-testid="quick-save"]').trigger('click')
    await flushPromises()

    expect(catalogApi.quickCreateSku).toHaveBeenCalledWith(expect.objectContaining({
      subCategoryId: subCategoryA.id, barcode: '6912345678903', productName: '新款篮球', skuCode: 'NEW-01',
    }))
    expect(wrapper.find('[data-testid="quick-create-dialog"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="pending-sku"]').text()).toContain('新款篮球')
    expect((wrapper.get('[data-testid="pending-quantity"]').element as HTMLInputElement).value).toBe('1')
    expect((wrapper.get('[data-testid="pending-unit-cost"]').element as HTMLInputElement).value).toBe('')
    expect(document.activeElement).toBe(wrapper.get('[data-testid="pending-quantity"]').element)
    expect(useInboundDraftStore().lines).toHaveLength(0)
  })

  it('loads later product pages so an existing SPU after the first 100 remains selectable', async () => {
    const product = (index: number) => ({
      id: `spu-${index}`, name: `商品${index}`, categoryId: categoryA.id, subCategoryId: subCategoryA.id, brandId: 'brand-1',
      imageUrl: null, description: null, enabled: true, skus: [],
    })
    catalogApi.getProducts
      .mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, index) => product(index + 1)), total: 101, page: 0, size: 100 })
      .mockResolvedValueOnce({ items: [product(101)], total: 101, page: 1, size: 100 })
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678101')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    expect(catalogApi.getProducts).toHaveBeenNthCalledWith(2, 1, 100)
    expect(wrapper.get('[data-testid="quick-create-dialog"]').text()).toContain('商品101')
  })

  it('rejects invalid quick-create money and warning stock before calling the API', async () => {
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678904')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-testid="quick-product-name"]').setValue('测试商品')
    await wrapper.get('[data-testid="quick-sku-code"]').setValue('TEST-01')
    await wrapper.get('[data-testid="quick-retail-price"]').setValue('99.999')
    await wrapper.get('[data-testid="quick-save"]').trigger('click')
    await flushPromises()

    expect(catalogApi.quickCreateSku).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="quick-create-dialog"]').text()).toContain('零售价最多保留 2 位小数')

    await wrapper.get('[data-testid="quick-retail-price"]').setValue('99.99')
    await wrapper.get('[data-testid="quick-warning-stock"]').setValue('1.5')
    await wrapper.get('[data-testid="quick-save"]').trigger('click')
    await flushPromises()
    expect(catalogApi.quickCreateSku).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="quick-create-dialog"]').text()).toContain('库存预警值必须是非负整数')
  })

  it('guards quick creation against concurrent form submissions', async () => {
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    catalogApi.quickCreateSku.mockReturnValue(new Promise(() => {}))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('6912345678905')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-testid="quick-product-name"]').setValue('测试商品')
    await wrapper.get('[data-testid="quick-sku-code"]').setValue('TEST-01')
    await wrapper.get('[data-testid="quick-retail-price"]').setValue('99')
    const form = wrapper.get('[data-testid="quick-create-dialog"] form')
    await Promise.all([form.trigger('submit'), form.trigger('submit')])

    expect(catalogApi.quickCreateSku).toHaveBeenCalledTimes(1)
  })

  it('reports an unknown barcode prefix and directs the administrator to catalog management', async () => {
    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    catalogApi.findCategoryByPrefix.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="barcode-input"]').setValue('7712345678901')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请先到商品管理建立对应大类')
    expect(wrapper.find('[data-testid="pending-sku"]').exists()).toBe(false)
  })

  it('keeps draft lines when a later scan has an unknown prefix', async () => {
    const wrapper = mountView()
    await flushPromises()
    const store = useInboundDraftStore()
    store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 })

    catalogApi.findSkuByBarcode.mockRejectedValue({ response: { status: 404 } })
    catalogApi.findCategoryByPrefix.mockRejectedValue({ response: { status: 404 } })
    await wrapper.get('[data-testid="barcode-input"]').setValue('7712345678902')
    await wrapper.get('[data-testid="scan-form"]').trigger('submit')
    await flushPromises()
    expect(store.lines).toHaveLength(1)
    expect(wrapper.get('[data-testid="draft-table"]').text()).toContain('训练篮球')
  })

  it('accumulates equal-cost scans and replaces or cancels a different-cost addition without duplicate SKU lines', async () => {
    const wrapper = mountView()
    await flushPromises()
    const store = useInboundDraftStore()

    expect(store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 }).kind).toBe('added')
    expect(store.addLine({ sku, productName: '训练篮球', quantity: 2, unitCost: 80 }).kind).toBe('merged')
    expect(store.lines[0]?.quantity).toBe(3)

    const conflict = store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 90 })
    expect(conflict.kind).toBe('price-conflict')
    expect(store.lines).toHaveLength(1)
    store.resolvePriceConflict('cancel')
    expect(store.lines).toHaveLength(1)
    expect(store.lines[0]).toMatchObject({ quantity: 3, unitCost: 80 })

    store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 90 })
    store.resolvePriceConflict('update')
    expect(store.lines).toHaveLength(1)
    expect(store.lines[0]).toMatchObject({ quantity: 4, unitCost: 90 })
    expect(new Set(store.lines.map((line) => line.sku.id)).size).toBe(store.lines.length)
  })

  it('rejects inbound costs with more than two decimal places before submission', async () => {
    mountView()
    await flushPromises()
    const store = useInboundDraftStore()

    expect(() => store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80.001 }))
      .toThrow('进价最多保留 2 位小数')
    expect(store.lines).toHaveLength(0)
  })

  it('enforces Java int limits for each line, merges, and the whole inbound document', async () => {
    mountView()
    await flushPromises()
    const store = useInboundDraftStore()
    const anotherSku = { ...sku, id: 'sku-2', skuCode: 'BALL-02', barcode: '6900000000013' }

    expect(() => store.addLine({ sku, productName: '训练篮球', quantity: 2_147_483_648, unitCost: 80 }))
      .toThrow('入库数量不能超过 2147483647')
    store.addLine({ sku, productName: '训练篮球', quantity: 2_147_483_647, unitCost: 80 })
    expect(() => store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 }))
      .toThrow('合并后的入库数量不能超过 2147483647')
    expect(store.lines[0]?.quantity).toBe(2_147_483_647)
    store.addLine({ sku: anotherSku, productName: '训练篮球', quantity: 1, unitCost: 80 })

    await expect(store.confirm()).rejects.toThrow('整单入库总数量不能超过 2147483647')
    expect(inboundApi.confirmInbound).not.toHaveBeenCalled()
  })

  it('rejects remarks longer than 500 characters before calling the API', async () => {
    const wrapper = mountView()
    await flushPromises()
    const store = useInboundDraftStore()
    store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 })
    store.remark = '备'.repeat(501)

    await expect(store.confirm()).rejects.toThrow('入库备注不能超过 500 个字符')
    expect(inboundApi.confirmInbound).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="inbound-remark"]').attributes('maxlength')).toBe('500')
  })

  it('validates quantity and cost, sends one idempotency key without occurredAt, and clears on success', async () => {
    inboundApi.confirmInbound.mockResolvedValue({
      id: 'in-1', orderNo: 'IN-20260805-000001', occurredAt: '2026-08-05T01:00:00Z',
      totalQuantity: 1, totalAmount: 80, remark: null, status: 'CONFIRMED',
      createdAt: '2026-08-05T01:00:00Z', lines: [],
    })
    const wrapper = mountView()
    await flushPromises()
    const store = useInboundDraftStore()
    store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 })
    await flushPromises()

    await wrapper.get('[data-testid="confirm-inbound"]').trigger('click')
    await wrapper.get('[data-testid="confirm-inbound"]').trigger('click')
    await flushPromises()

    expect(inboundApi.confirmInbound).toHaveBeenCalledTimes(1)
    const [request, requestId] = inboundApi.confirmInbound.mock.calls[0]!
    expect(requestId).toMatch(/^[0-9a-f-]{36}$/)
    expect(request).toEqual({ remark: '', lines: [{ skuId: sku.id, quantity: 1, unitCost: 80 }] })
    expect(request).not.toHaveProperty('occurredAt')
    expect(store.lines).toHaveLength(0)
    expect(wrapper.text()).toContain('IN-20260805-000001')
  })

  it('keeps the idempotency key for an unchanged retry but renews it after the remark changes', async () => {
    inboundApi.confirmInbound
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce({ id: 'in-1', orderNo: 'IN-20260805-000001', lines: [] })
    const wrapper = mountView()
    await flushPromises()
    const store = useInboundDraftStore()
    store.addLine({ sku, productName: '训练篮球', quantity: 1, unitCost: 80 })

    await flushPromises()
    await wrapper.get('[data-testid="confirm-inbound"]').trigger('click')
    await flushPromises()
    const originalKey = inboundApi.confirmInbound.mock.calls[0]![1]
    await wrapper.get('[data-testid="confirm-inbound"]').trigger('click')
    await flushPromises()
    expect(inboundApi.confirmInbound.mock.calls[1]![1]).toBe(originalKey)

    await wrapper.get('[data-testid="inbound-remark"]').setValue('改过的备注')
    await wrapper.get('[data-testid="confirm-inbound"]').trigger('click')
    await flushPromises()
    expect(inboundApi.confirmInbound.mock.calls[2]![1]).not.toBe(originalKey)
  })
})
