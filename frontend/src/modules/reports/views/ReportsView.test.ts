import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ReportsView from './ReportsView.vue'

const reportApi = vi.hoisted(() => ({
  getSalesSummary: vi.fn(), getProductRanking: vi.fn(), getCategoryShare: vi.fn(),
  getInboundSummary: vi.fn(), getInventoryValuation: vi.fn(), getLowStock: vi.fn(),
}))
const exportTools = vi.hoisted(() => ({ downloadRankingCsv: vi.fn() }))
vi.mock('../api', () => reportApi)
vi.mock('../exportReportsCsv', () => exportTools)
vi.mock('../components/SalesTrendChart.vue', () => ({ default: { template: '<div data-testid="trend-chart" />' } }))

const emptySales = { grossSalesAmount: '0.00', refundAmount: '0.00', netSalesAmount: '0.00',
  grossProfit: '0.00', orderCount: 0, netQuantity: 0, trend: [] }

describe('ReportsView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-08-24T03:00:00Z'))
    reportApi.getSalesSummary.mockReset().mockResolvedValue(emptySales)
    reportApi.getProductRanking.mockReset().mockResolvedValue([])
    reportApi.getCategoryShare.mockReset().mockResolvedValue([])
    reportApi.getInboundSummary.mockReset().mockResolvedValue({ orderCount: 0, totalQuantity: 0, totalAmount: '0.00' })
    reportApi.getInventoryValuation.mockReset().mockResolvedValue({ skuCount: 0, totalQuantity: 0, totalCost: '0.0000' })
    reportApi.getLowStock.mockReset().mockResolvedValue([])
    exportTools.downloadRankingCsv.mockReset()
  })

  afterEach(() => vi.useRealTimers())

  it('loads today by default and presents zero-valued empty states', async () => {
    const wrapper = mount(ReportsView)
    await flushPromises()

    expect(reportApi.getSalesSummary).toHaveBeenCalledWith('2026-08-24', '2026-08-24')
    expect(wrapper.text()).toContain('¥0.00')
    expect(wrapper.text()).toContain('暂无销售趋势')
    expect(wrapper.text()).toContain('暂无商品排行')
  })

  it('reloads for month and custom ranges', async () => {
    const wrapper = mount(ReportsView)
    await flushPromises()

    await wrapper.get('[data-testid="range-month"]').trigger('click')
    await flushPromises()
    expect(reportApi.getSalesSummary).toHaveBeenLastCalledWith('2026-08-01', '2026-08-31')

    await wrapper.get('[data-testid="range-custom"]').trigger('click')
    await wrapper.get('[data-testid="from-date"]').setValue('2026-08-10')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-20')
    await wrapper.get('[data-testid="apply-range"]').trigger('click')
    await flushPromises()
    expect(reportApi.getSalesSummary).toHaveBeenLastCalledWith('2026-08-10', '2026-08-20')
  })

  it('blocks custom ranges longer than 366 days without requesting data', async () => {
    const wrapper = mount(ReportsView)
    await flushPromises()
    const callsBefore = reportApi.getSalesSummary.mock.calls.length

    await wrapper.get('[data-testid="range-custom"]').trigger('click')
    await wrapper.get('[data-testid="from-date"]').setValue('2025-01-01')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-24')
    await wrapper.get('[data-testid="apply-range"]').trigger('click')

    expect(wrapper.get('[role="alert"]').text()).toContain('366')
    expect(reportApi.getSalesSummary).toHaveBeenCalledTimes(callsBefore)
  })

  it('exports ranking rows with the currently applied range', async () => {
    reportApi.getProductRanking.mockResolvedValue([{ skuId: 'sku-1', skuCode: 'BALL-1', barcode: '69001',
      productName: '篮球', grossQuantity: 2, returnedQuantity: 1, netQuantity: 1, netSalesAmount: '90.00' }])
    const wrapper = mount(ReportsView)
    await flushPromises()
    await wrapper.get('[data-testid="range-custom"]').trigger('click')
    await wrapper.get('[data-testid="from-date"]').setValue('2026-08-10')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-20')
    await wrapper.get('[data-testid="apply-range"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="export-ranking"]').trigger('click')

    expect(exportTools.downloadRankingCsv).toHaveBeenCalledWith(expect.any(Array), '2026-08-10', '2026-08-20')
  })

  it('keeps the previous applied range and data when a replacement load fails', async () => {
    reportApi.getProductRanking.mockResolvedValue([{ skuId: 'sku-1', skuCode: 'BALL-1', barcode: '69001',
      productName: '旧范围篮球', grossQuantity: 1, returnedQuantity: 0, netQuantity: 1, netSalesAmount: '90.00' }])
    const wrapper = mount(ReportsView)
    await flushPromises()
    reportApi.getSalesSummary.mockRejectedValueOnce(new Error('报表加载失败'))

    await wrapper.get('[data-testid="range-custom"]').trigger('click')
    await wrapper.get('[data-testid="from-date"]').setValue('2026-08-10')
    await wrapper.get('[data-testid="to-date"]').setValue('2026-08-20')
    await wrapper.get('[data-testid="apply-range"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('报表加载失败')
    expect(wrapper.text()).toContain('2026-08-24 至 2026-08-24')
    expect(wrapper.text()).toContain('旧范围篮球')
    await wrapper.get('[data-testid="export-ranking"]').trigger('click')
    expect(exportTools.downloadRankingCsv).toHaveBeenCalledWith(expect.any(Array), '2026-08-24', '2026-08-24')
  })
})
