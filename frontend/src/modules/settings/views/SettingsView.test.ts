import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SettingsView from './SettingsView.vue'

const api = vi.hoisted(() => ({
  getStoreSetting: vi.fn(), updateStoreSetting: vi.fn(),
  getReceiptSetting: vi.fn(), updateReceiptSetting: vi.fn(),
  getDocumentNumbering: vi.fn(), updateDocumentNumbering: vi.fn(),
  getPaymentMethods: vi.fn(), createPaymentMethod: vi.fn(), patchPaymentMethod: vi.fn(),
  getOperationLogs: vi.fn(),
  getBackups: vi.fn(), createBackup: vi.fn(), previewRestore: vi.fn(), restoreBackup: vi.fn(),
}))
vi.mock('../api', () => api)

const store = { storeName: '体育商品门店', phone: '', address: '', deviceName: '默认收银台', updatedAt: '2026-08-24T01:00:00Z' }
const receipt = { headerText: '', footerText: '谢谢惠顾', showPhone: true, showAddress: true, paperWidth: 58, updatedAt: '2026-08-24T01:00:00Z' }

describe('SettingsView', () => {
  beforeEach(() => {
    api.getStoreSetting.mockReset().mockResolvedValue(store)
    api.updateStoreSetting.mockReset().mockImplementation(async value => ({ ...value, updatedAt: store.updatedAt }))
    api.getReceiptSetting.mockReset().mockResolvedValue(receipt)
    api.updateReceiptSetting.mockReset().mockImplementation(async value => ({ ...value, updatedAt: receipt.updatedAt }))
    api.getDocumentNumbering.mockReset().mockResolvedValue([{ documentType: 'SALE', prefix: 'SO', nextValue: 1, updatedAt: store.updatedAt }])
    api.updateDocumentNumbering.mockReset().mockImplementation(async (_type, value) => ({ documentType: 'SALE', ...value, updatedAt: store.updatedAt }))
    api.getPaymentMethods.mockReset().mockResolvedValue([{ code: 'CASH', name: '现金', enabled: true, sortOrder: 10 }])
    api.createPaymentMethod.mockReset().mockResolvedValue({ code: 'UNIONPAY', name: '云闪付', enabled: true, sortOrder: 50 })
    api.patchPaymentMethod.mockReset().mockImplementation(async (_code, value) => ({ code: 'CASH', name: '现金', enabled: value.enabled, sortOrder: 10 }))
    api.getOperationLogs.mockReset().mockResolvedValue({ items: [{ id: 'log-1', operationType: 'SALE', objectType: 'SALE_ORDER', objectId: 'sale-1', occurredAt: '2026-08-24T01:00:00Z', result: 'SUCCESS', message: 'HTTP 201', deviceSummary: '默认收银台 · scanner/1.0' }], total: 1, page: 0, size: 20 })
    api.getBackups.mockReset().mockResolvedValue([])
  })

  it('loads all settings and exposes a read-only operation log without login controls', async () => {
    const wrapper = mount(SettingsView)
    await flushPromises()

    expect(wrapper.text()).toContain('门店资料')
    expect(wrapper.text()).toContain('小票设置')
    expect(wrapper.text()).toContain('现金')
    expect(wrapper.text()).toContain('销售')
    expect(wrapper.text()).toContain('默认收银台 · scanner/1.0')
    expect(wrapper.text()).not.toMatch(/登录|退出登录|密码/)
    expect(wrapper.find('[data-testid="operation-log-edit"]').exists()).toBe(false)
  })

  it('saves store receipt and numbering changes', async () => {
    const wrapper = mount(SettingsView)
    await flushPromises()
    await wrapper.get('[data-testid="store-name"]').setValue('冠军体育')
    await wrapper.get('[data-testid="device-name"]').setValue('一号收银台')
    await wrapper.get('[data-testid="save-store"]').trigger('click')
    await wrapper.get('[data-testid="paper-width"]').setValue('80')
    await wrapper.get('[data-testid="save-receipt"]').trigger('click')
    await wrapper.get('[data-testid="number-prefix-SALE"]').setValue('XS')
    await wrapper.get('[data-testid="save-number-SALE"]').trigger('click')
    await flushPromises()

    expect(api.updateStoreSetting).toHaveBeenCalledWith(expect.objectContaining({ storeName: '冠军体育', deviceName: '一号收银台' }))
    expect(api.updateReceiptSetting).toHaveBeenCalledWith(expect.objectContaining({ paperWidth: 80 }))
    expect(api.updateDocumentNumbering).toHaveBeenCalledWith('SALE', { prefix: 'XS', nextValue: 1 })
  })

  it('adds and disables a payment method', async () => {
    const wrapper = mount(SettingsView)
    await flushPromises()
    await wrapper.get('[data-testid="payment-code"]').setValue('UNIONPAY')
    await wrapper.get('[data-testid="payment-name"]').setValue('云闪付')
    await wrapper.get('[data-testid="add-payment"]').trigger('click')
    await flushPromises()
    expect(api.createPaymentMethod).toHaveBeenCalledWith({ code: 'UNIONPAY', name: '云闪付', sortOrder: 50 })

    await wrapper.get('[data-testid="toggle-payment-CASH"]').trigger('click')
    await flushPromises()
    expect(api.patchPaymentMethod).toHaveBeenCalledWith('CASH', { enabled: false })
  })
})
