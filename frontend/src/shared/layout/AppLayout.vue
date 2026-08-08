<script setup lang="ts">
import { ElAside, ElContainer, ElMain, ElMenu, ElMenuItem } from 'element-plus'

const navigationItems = [
  { label: '首页', path: '/' },
  { label: '商品管理', path: '/catalog' },
  { label: '进货入库', path: '/inbounds' },
  { label: '库存管理', path: '/inventory' },
  { label: '零售收银', path: '/sales' },
  { label: '销售退货', path: '/returns' },
  { label: '统计报表', path: '/reports' },
  { label: '系统设置', path: '/settings' },
]
</script>

<template>
  <ElContainer class="app-layout">
    <ElAside width="200px">
      <nav aria-label="主导航">
        <ElMenu router>
          <ElMenuItem v-for="item in navigationItems" :key="item.path" :index="item.path">
            {{ item.label }}
          </ElMenuItem>
        </ElMenu>
      </nav>
    </ElAside>
    <ElMain>
      <slot />
    </ElMain>
  </ElContainer>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
}
</style>
