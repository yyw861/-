import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppLayout from './AppLayout.vue'

describe('AppLayout', () => {
  it('shows every primary navigation item', () => {
    const wrapper = mount(AppLayout)

    for (const label of ['首页', '商品管理', '进货入库', '库存管理', '零售收银', '销售退货', '统计报表', '系统设置']) {
      expect(wrapper.text()).toContain(label)
    }
  })
})
