import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'

const dashboardApi = vi.hoisted(() => ({ getDashboard: vi.fn() }))
vi.mock('../api', () => dashboardApi)

describe('DashboardView', () => {
  beforeEach(() => dashboardApi.getDashboard.mockReset().mockResolvedValue({
    date:'2026-08-24',salesAmount:'290.00',salesOrderCount:2,grossProfit:'120.00',
    inboundAmount:'300.00',inboundQuantity:5,inventoryQuantity:3,inventoryValue:'290.0000',lowStockCount:1,
    topProducts:[{skuId:'sku-1',skuCode:'BALL-1',barcode:'69001',productName:'篮球',grossQuantity:2,returnedQuantity:1,netQuantity:1,netSalesAmount:'90.00'}],
    recentDocuments:[{documentType:'SALE',id:'sale-1',orderNo:'SO-1',occurredAt:'2026-08-24T01:00:00Z',amount:'180.00'}],
  }))

  it('renders today metrics, low-stock warning, ranking and recent documents', async () => {
    const wrapper = mount(DashboardView)
    await flushPromises()

    expect(dashboardApi.getDashboard).toHaveBeenCalledWith(undefined)
    expect(wrapper.text()).toContain('¥290.00')
    expect(wrapper.text()).toContain('低库存商品 1 种')
    expect(wrapper.text()).toContain('篮球')
    expect(wrapper.text()).toContain('SO-1')
  })
})
