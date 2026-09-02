import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import CatalogView from './CatalogView.vue'

const api = vi.hoisted(() => ({
  getCategories: vi.fn(), getSubCategories: vi.fn(), getBrands: vi.fn(), getProducts: vi.fn(),
  createCategory: vi.fn(), updateCategory: vi.fn(), createSubCategory: vi.fn(), updateSubCategory: vi.fn(), createBrand: vi.fn(), updateBrand: vi.fn(),
  createProduct: vi.fn(), quickCreateSku: vi.fn(), updateProduct: vi.fn(), updateSku: vi.fn(), setSkuEnabled: vi.fn(),
}))

vi.mock('../api', () => api)

describe('CatalogView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    api.getCategories.mockResolvedValue([{ id: 'cat-1', code: '69', name: '球类', sortOrder: 0, enabled: true }])
    api.getSubCategories.mockResolvedValue([{ id: 'sub-1', categoryId: 'cat-1', code: '01', name: '篮球', sortOrder: 0, enabled: true }])
    api.getBrands.mockResolvedValue([{ id: 'brand-1', name: '飞跃', remark: null, enabled: true }])
    api.getProducts.mockResolvedValue({
      items: [{
        id: 'spu-1', name: '训练篮球', categoryId: 'cat-1', subCategoryId: 'sub-1', brandId: 'brand-1',
        imageUrl: 'https://example.com/ball.jpg', description: '室内外通用', enabled: true,
        skus: [{ id: 'sku-1', spuId: 'spu-1', skuCode: 'BALL-01', barcode: '6900000000012',
          specs: { 颜色: '红色', 尺码: '7号' }, retailPrice: 129, warningStock: 3, enabled: true }],
      }], total: 1, page: 0, size: 20,
    })
  })

  it('provides category, brand, product media, description, specs, pricing, warning and enable controls', async () => {
    const wrapper = mount(CatalogView)
    await flushPromises()

    for (const label of ['大类管理', '小类管理', '品牌管理', '图片 URL', '商品描述', '规格', '零售价', '库存预警值', '启用状态']) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.text()).toContain('训练篮球')
    expect(wrapper.text()).toContain('颜色：红色')
  })

  it('edits independently numbered major and minor categories', async () => {
    api.updateCategory.mockResolvedValue({ id: 'cat-1', code: '69', name: '球类器材', sortOrder: 0, enabled: true })
    api.updateSubCategory.mockResolvedValue({ id: 'sub-1', categoryId: 'cat-1', code: '01', name: '篮球器材', sortOrder: 0, enabled: true })
    const wrapper = mount(CatalogView)
    await flushPromises()

    await wrapper.get('[data-testid="category-name-cat-1"]').setValue('球类器材')
    await wrapper.get('[data-testid="save-category-cat-1"]').trigger('click')
    await wrapper.get('[data-testid="subcategory-name-sub-1"]').setValue('篮球器材')
    await wrapper.get('[data-testid="save-subcategory-sub-1"]').trigger('click')
    await flushPromises()

    expect(api.updateCategory).toHaveBeenCalledWith('cat-1', { code: '69', name: '球类器材', sortOrder: 0, enabled: true })
    expect(api.updateSubCategory).toHaveBeenCalledWith('cat-1', 'sub-1', {
      code: '01', name: '篮球器材', sortOrder: 0, enabled: true,
    })
  })

  it('edits a product and all SKU fields in one atomic product request', async () => {
    api.updateProduct.mockResolvedValue({})
    api.updateSku.mockResolvedValue({})
    const wrapper = mount(CatalogView)
    await flushPromises()
    await wrapper.get('[data-testid="edit-product-spu-1"]').trigger('click')
    await wrapper.get('[data-testid="product-image-url"]').setValue('https://example.com/new.jpg')
    await wrapper.get('[data-testid="product-description"]').setValue('新版描述')
    await wrapper.get('[data-testid="sku-retail-price-sku-1"]').setValue('139')
    await wrapper.get('[data-testid="sku-warning-stock-sku-1"]').setValue('5')
    await wrapper.get('[data-testid="save-product"]').trigger('click')
    await flushPromises()

    expect(api.updateProduct).toHaveBeenCalledWith('spu-1', expect.objectContaining({
      imageUrl: 'https://example.com/new.jpg', description: '新版描述',
      skus: [expect.objectContaining({
        skuId: 'sku-1', retailPrice: 139, warningStock: 5, specs: { 颜色: '红色', 尺码: '7号' },
      })],
    }))
    expect(api.updateSku).not.toHaveBeenCalled()
  })

  it('edits category ordering and brand remarks in addition to their names and status', async () => {
    api.updateCategory.mockResolvedValue({ id: 'cat-1', code: '69', name: '专业球类', sortOrder: 10, enabled: true })
    api.updateBrand.mockResolvedValue({ id: 'brand-1', name: '飞跃体育', remark: '国产品牌', enabled: true })
    const wrapper = mount(CatalogView)
    await flushPromises()

    await wrapper.get('[data-testid="category-name-cat-1"]').setValue('专业球类')
    await wrapper.get('[data-testid="category-sort-cat-1"]').setValue('10')
    await wrapper.get('[data-testid="save-category-cat-1"]').trigger('click')
    await wrapper.get('[data-testid="brand-name-brand-1"]').setValue('飞跃体育')
    await wrapper.get('[data-testid="brand-remark-brand-1"]').setValue('国产品牌')
    await wrapper.get('[data-testid="save-brand-brand-1"]').trigger('click')
    await flushPromises()

    expect(api.updateCategory).toHaveBeenCalledWith('cat-1', { code: '69', name: '专业球类', sortOrder: 10, enabled: true })
    expect(api.updateBrand).toHaveBeenCalledWith('brand-1', { name: '飞跃体育', remark: '国产品牌', enabled: true })
  })

  it('loads every catalog page instead of silently stopping at 100 products', async () => {
    const product = (index: number) => ({
      id: `spu-${index}`, name: `商品${index}`, categoryId: 'cat-1', subCategoryId: 'sub-1', brandId: 'brand-1',
      imageUrl: null, description: null, enabled: true, skus: [],
    })
    api.getProducts
      .mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, index) => product(index + 1)), total: 101, page: 0, size: 100 })
      .mockResolvedValueOnce({ items: [product(101)], total: 101, page: 1, size: 100 })

    const wrapper = mount(CatalogView)
    await flushPromises()

    expect(api.getProducts).toHaveBeenNthCalledWith(1, 0, 100)
    expect(api.getProducts).toHaveBeenNthCalledWith(2, 1, 100)
    expect(wrapper.text()).toContain('商品101')
  })

  it('rejects invalid SKU prices and warning stock before the atomic save request', async () => {
    const wrapper = mount(CatalogView)
    await flushPromises()
    await wrapper.get('[data-testid="edit-product-spu-1"]').trigger('click')
    await wrapper.get('[data-testid="sku-retail-price-sku-1"]').setValue('139.999')
    await wrapper.get('[data-testid="save-product"]').trigger('click')
    await flushPromises()
    expect(api.updateProduct).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('零售价最多保留 2 位小数')

    await wrapper.get('[data-testid="sku-retail-price-sku-1"]').setValue('139.99')
    await wrapper.get('[data-testid="sku-warning-stock-sku-1"]').setValue('2.5')
    await wrapper.get('[data-testid="save-product"]').trigger('click')
    await flushPromises()
    expect(api.updateProduct).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('库存预警值必须是非负整数')
  })

  it('moves focus into the edit dialog and closes it with Escape', async () => {
    const wrapper = mount(CatalogView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('[data-testid="edit-product-spu-1"]').trigger('click')
    await flushPromises()

    const close = wrapper.get('[data-testid="edit-product-close"]')
    const trigger = wrapper.get('[data-testid="edit-product-spu-1"]')
    expect(document.activeElement).toBe(close.element)
    await wrapper.get('[aria-labelledby="edit-title"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[aria-labelledby="edit-title"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
  })

  it('rejects a non-integer category sort before calling the API', async () => {
    const wrapper = mount(CatalogView)
    await flushPromises()
    await wrapper.get('[data-testid="category-sort-cat-1"]').setValue('1.5')
    await wrapper.get('[data-testid="save-category-cat-1"]').trigger('click')
    await flushPromises()

    expect(api.updateCategory).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('分类排序必须是 Java int 范围内的整数')
  })

  it('guards atomic product saves against concurrent form submissions', async () => {
    api.updateProduct.mockReturnValue(new Promise(() => {}))
    const wrapper = mount(CatalogView)
    await flushPromises()
    await wrapper.get('[data-testid="edit-product-spu-1"]').trigger('click')
    const form = wrapper.get('form.dialog')
    await Promise.all([form.trigger('submit'), form.trigger('submit')])

    expect(api.updateProduct).toHaveBeenCalledTimes(1)
  })

  it('creates a SKU for a zero-SKU product and includes every field in the next atomic product save', async () => {
    const zeroSkuProduct = {
      id: 'spu-empty', name: '空白商品', categoryId: 'cat-1', subCategoryId: 'sub-1', brandId: 'brand-1',
      imageUrl: null, description: null, enabled: true, skus: [],
    }
    const createdSku = {
      id: 'sku-new', spuId: 'spu-empty', skuCode: 'NEW-42', barcode: '6900000000042',
      specs: { 颜色: '蓝色', 尺码: '42' }, retailPrice: 199, warningStock: 4, enabled: true,
    }
    api.getProducts.mockResolvedValue({ items: [zeroSkuProduct], total: 1, page: 0, size: 100 })
    api.quickCreateSku.mockResolvedValue(createdSku)
    api.updateProduct.mockResolvedValue({ ...zeroSkuProduct, skus: [{ ...createdSku, enabled: false }] })
    const wrapper = mount(CatalogView)
    await flushPromises()
    await wrapper.get('[data-testid="edit-product-spu-empty"]').trigger('click')
    await wrapper.get('[data-testid="add-sku"]').trigger('click')
    await wrapper.get('[data-testid="new-sku-code"]').setValue('NEW-42')
    await wrapper.get('[data-testid="new-sku-barcode"]').setValue('6900000000042')
    await wrapper.get('[data-testid="new-sku-specs"]').setValue('颜色:蓝色,尺码:42')
    await wrapper.get('[data-testid="new-sku-retail-price"]').setValue('199')
    await wrapper.get('[data-testid="new-sku-warning-stock"]').setValue('4')
    await wrapper.get('[data-testid="new-sku-enabled"]').setValue(false)
    await wrapper.get('[data-testid="create-sku"]').trigger('click')
    await flushPromises()

    expect(api.quickCreateSku).toHaveBeenCalledWith({
      existingSpuId: 'spu-empty', subCategoryId: 'sub-1', brandId: 'brand-1', productName: '空白商品',
      skuCode: 'NEW-42', barcode: '6900000000042', specs: { 颜色: '蓝色', 尺码: '42' },
      retailPrice: 199, warningStock: 4,
    })
    await wrapper.get('[data-testid="save-product"]').trigger('click')
    await flushPromises()
    expect(api.updateProduct).toHaveBeenCalledWith('spu-empty', expect.objectContaining({
      skus: [expect.objectContaining({
        skuId: 'sku-new', skuCode: 'NEW-42', barcode: '6900000000042',
        specs: { 颜色: '蓝色', 尺码: '42' }, retailPrice: 199, warningStock: 4, enabled: false,
      })],
    }))
  })
})
