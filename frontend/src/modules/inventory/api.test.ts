import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAdjustment, getInventory } from './api'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/shared/api/http', () => ({ http: httpMock }))

describe('inventory API', () => {
  beforeEach(() => {
    httpMock.get.mockReset().mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 50 } })
    httpMock.post.mockReset().mockResolvedValue({ data: { id: 'adjustment-1', lines: [] } })
  })

  it.each([
    ['name', { name: '训练篮球' }, { name: '训练篮球', page: 0, size: 50 }],
    ['skuCode', { skuCode: 'BALL-01' }, { skuCode: 'BALL-01', page: 0, size: 50 }],
    ['barcode', { barcode: '6900000000012' }, { barcode: '6900000000012', page: 0, size: 50 }],
  ])('sends the backend %s search parameter', async (_field, query, expected) => {
    await getInventory(query)

    expect(httpMock.get).toHaveBeenCalledWith('/inventory', { params: expected })
  })

  it('sends low-stock filtering only when enabled', async () => {
    await getInventory({ lowStock: true })

    expect(httpMock.get).toHaveBeenCalledWith('/inventory', {
      params: { lowStock: true, page: 0, size: 50 },
    })
  })

  it('posts an adjustment with its idempotency key', async () => {
    const command = { lines: [{ skuId: 'sku-1', systemQuantity: 10, countedQuantity: 8, reason: '破损' }] }

    await createAdjustment(command, 'request-1')

    expect(httpMock.post).toHaveBeenCalledWith('/inventory/adjustments', command, {
      headers: { 'Idempotency-Key': 'request-1' },
    })
  })
})
