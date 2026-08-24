import { describe, expect, it } from 'vitest'

import { inventoryCsv } from './exportInventoryCsv'

describe('inventoryCsv', () => {
  it('exports only supplied filtered rows with an UTF-8 BOM and escaped text', () => {
    const csv = inventoryCsv([{
      skuId: 'sku-1', productId: 'spu-1', productName: '篮球,专业款', categoryId: 'category-1',
      categoryName: '球类', brandId: 'brand-1', brandName: '品牌A', skuCode: 'BALL-1', barcode: '69001',
      retailPrice: 159, warningStock: 3, enabled: true, quantity: 2, averageCost: 100.1234,
      inventoryValue: 200.2468, version: 1, updatedAt: '2026-08-24T08:00:00Z',
    }])

    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('商品名称,SKU编码,条码')
    expect(csv).toContain('"篮球,专业款"')
    expect(csv).toContain("'69001")
    expect(csv).toContain('100.1234')
  })

  it('neutralizes spreadsheet formulas in text fields', () => {
    const csv = inventoryCsv([{
      skuId: 'sku-2', productId: 'spu-2', productName: '=HYPERLINK("bad")', categoryId: 'category-1',
      categoryName: '+分类', brandId: 'brand-1', brandName: '@品牌', skuCode: '-SKU', barcode: '00123',
      retailPrice: 1, warningStock: 0, enabled: true, quantity: 1, averageCost: 1,
      inventoryValue: 1, version: 1, updatedAt: '2026-08-24T08:00:00Z',
    }])

    expect(csv).toContain("'=HYPERLINK")
    expect(csv).toContain("'+分类")
    expect(csv).toContain("'-SKU")
    expect(csv).toContain("'00123")
  })
})
