<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { errorMessage } from '../../catalog/api'
import { getInventory, getStockMovements } from '../api'
import type { InventoryItem, StockMovement } from '../types'
import { formatBusinessDateTime } from '@/shared/format/dateTime'

const items = ref<InventoryItem[]>([])
const movements = ref<StockMovement[]>([])
const selected = ref<InventoryItem | null>(null)
const keyword = ref('')
const loading = ref(false)
const alert = ref('')

onMounted(load)

async function load() {
  loading.value = true
  alert.value = ''
  try {
    items.value = (await getInventory({ keyword: keyword.value })).items
  } catch (cause) {
    alert.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function showMovements(item: InventoryItem) {
  alert.value = ''
  try {
    movements.value = await getStockMovements(item.skuId)
    selected.value = item
  } catch (cause) {
    alert.value = errorMessage(cause)
  }
}

function closeMovements() {
  selected.value = null
  movements.value = []
}

function signedQuantity(value: number) {
  return value > 0 ? `+${value}` : String(value)
}
</script>

<template>
  <main class="page">
    <header><p class="eyebrow">库存查询</p><h1>库存管理</h1><p>库存数量只通过入库、销售、退货或调整单改变。</p></header>
    <p v-if="alert" role="alert" class="alert">{{ alert }}</p>

    <section class="card" aria-labelledby="inventory-balance-title">
      <div class="section-heading">
        <div><h2 id="inventory-balance-title">当前库存余额</h2><p>查看 SKU 数量、移动平均成本和库存金额。</p></div>
        <form class="search" role="search" @submit.prevent="load"><label>商品名称<input v-model="keyword" placeholder="输入商品名称"></label><button type="submit">查询</button></form>
      </div>
      <p v-if="loading">正在加载…</p>
      <div v-else class="table-wrap">
        <table><thead><tr><th>商品</th><th>SKU 编码</th><th>条码</th><th>分类 / 品牌</th><th>数量</th><th>平均成本</th><th>库存金额</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in items" :key="item.skuId">
              <td>{{ item.productName }}</td><td>{{ item.skuCode }}</td><td>{{ item.barcode }}</td><td>{{ item.categoryName }} / {{ item.brandName }}</td>
              <td>{{ item.quantity }}</td><td>{{ Number(item.averageCost).toFixed(4) }}</td><td>¥{{ Number(item.inventoryValue).toFixed(2) }}</td>
              <td><button type="button" class="link" @click="showMovements(item)">查看流水</button></td>
            </tr>
            <tr v-if="items.length === 0"><td colspan="8" class="empty">暂无库存记录</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="selected" class="overlay" role="dialog" aria-modal="true" aria-labelledby="movement-title" @keydown.esc="closeMovements">
      <section class="dialog">
        <header class="section-heading"><div><h2 id="movement-title">库存流水</h2><p>{{ selected.productName }} · {{ selected.skuCode }}</p></div><button type="button" class="close" aria-label="关闭" @click="closeMovements">×</button></header>
        <div class="table-wrap"><table><thead><tr><th>发生时间</th><th>类型</th><th>来源单号</th><th>变动数量</th><th>变动前</th><th>变动后</th><th>单位成本</th></tr></thead>
          <tbody><tr v-for="movement in movements" :key="movement.id"><td>{{ formatBusinessDateTime(movement.occurredAt) }}</td><td>{{ movement.movementType }}</td><td>{{ movement.documentNo }}</td><td>{{ signedQuantity(movement.quantityDelta) }}</td><td>{{ movement.quantityBefore }}</td><td>{{ movement.quantityAfter }}</td><td>{{ Number(movement.unitCost).toFixed(4) }}</td></tr></tbody>
        </table></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.page { max-width: 82rem; margin: 0 auto; display: grid; gap: 1rem; color: #0f172a; } h1, h2, p { margin-top: 0; }.eyebrow { color: #2563eb; font-size: .78rem; font-weight: 800; margin-bottom: .3rem; }
.card, .dialog { background: white; border: 1px solid #e2e8f0; border-radius: .75rem; padding: 1.2rem; }.section-heading, .search { display: flex; align-items: end; justify-content: space-between; gap: .75rem; }.section-heading p { margin-bottom: 0; color: #64748b; }
label { display: grid; gap: .3rem; color: #334155; font-size: .88rem; } input { font: inherit; border: 1px solid #cbd5e1; border-radius: .4rem; padding: .6rem; } button { font: inherit; border: 0; border-radius: .4rem; padding: .65rem .9rem; color: white; background: #2563eb; cursor: pointer; }.link, .close { background: none; color: #2563eb; padding: .2rem; }.close { color: #334155; font-size: 1.5rem; }
.table-wrap { overflow-x: auto; margin-top: 1rem; } table { width: 100%; border-collapse: collapse; } th, td { padding: .7rem; text-align: left; border-top: 1px solid #e2e8f0; white-space: nowrap; } th { color: #475569; font-size: .82rem; }.empty { color: #94a3b8; text-align: center; }.alert { color: #b91c1c; }
.overlay { position: fixed; inset: 0; z-index: 20; background: rgb(15 23 42 / .5); display: grid; place-items: center; padding: 1rem; }.dialog { width: min(70rem, 100%); max-height: 90vh; overflow: auto; }
@media (max-width: 750px) { .section-heading { align-items: stretch; flex-direction: column; }.search { align-items: stretch; flex-direction: column; } }
</style>
