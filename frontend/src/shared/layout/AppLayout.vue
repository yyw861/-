<script setup lang="ts">
import { ElAside, ElContainer, ElMain, ElMenu, ElMenuItem } from 'element-plus'

const navigationItems = [
  '首页',
  '商品管理',
  '进货入库',
  '库存管理',
  '零售收银',
  '销售退货',
  '统计报表',
  '系统设置',
]
</script>

<template>
  <ElContainer class="app-layout">
    <ElAside width="200px">
      <nav aria-label="主导航">
        <ElMenu default-active="首页">
          <ElMenuItem v-for="item in navigationItems" :key="item" :index="item">
            {{ item }}
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
