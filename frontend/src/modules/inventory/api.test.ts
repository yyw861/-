import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getInventory } from './api'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/shared/api/http', () => ({ http: httpMock }))

describe('inventory API', () => {
  beforeEach(() => {
    httpMock.get.mockReset().mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 50 } })
  })

  it.each([
    ['name', { name: '训练篮球' }, { name: '训练篮球', page: 0, size: 50 }],
    ['skuCode', { skuCode: 'BALL-01' }, { skuCode: 'BALL-01', page: 0, size: 50 }],
    ['barcode', { barcode: '6900000000012' }, { barcode: '6900000000012', page: 0, size: 50 }],
  ])('sends the backend %s search parameter', async (_field, query, expected) => {
    await getInventory(query)

    expect(httpMock.get).toHaveBeenCalledWith('/inventory', { params: expected })
  })
})
