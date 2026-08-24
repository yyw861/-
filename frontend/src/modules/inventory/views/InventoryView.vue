<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { errorMessage } from '../../catalog/api'
import AdjustmentDialog from '../components/AdjustmentDialog.vue'
import { getInventory, getStockMovements } from '../api'
import { downloadInventoryCsv } from '../exportInventoryCsv'
import type { InventoryQuery } from '../api'
import type { InventoryItem, StockMovement } from '../types'
import { formatBusinessDateTime } from '@/shared/format/dateTime'

const items = ref<InventoryItem[]>([])
const movements = ref<StockMovement[]>([])
const selected = ref<InventoryItem | null>(null)
const adjusting = ref<InventoryItem | null>(null)
const keyword = ref('')
const lowStockOnly = ref(false)
const searchField = ref<'name' | 'skuCode' | 'barcode'>('name')
const loading = ref(false)
const exportLoading = ref(false)
const alert = ref('')
const page = ref(0)
const total = ref(0)
const appliedFilter = ref<InventoryQuery>({})
const pageSize = 50
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

onMounted(load)

async function load(filter: InventoryQuery = appliedFilter.value) {
  loading.value = true
  alert.value = ''
  try {
    const result = await getInventory({ ...filter, page: page.value, size: pageSize })
    items.value = result.items
    total.value = result.total
    page.value = result.page
    appliedFilter.value = { ...filter }
  } catch (cause) {
    alert.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function search() {
  page.value = 0
  await load(activeFilter())
}

function activeFilter(): InventoryQuery {
  const value = keyword.value.trim()
  return { ...(value ? { [searchField.value]: value } : {}),
    ...(lowStockOnly.value ? { lowStock: true } : {}) }
}

async function exportCurrent() {
  if (exportLoading.value) return
  exportLoading.value = true
  alert.value = ''
  try {
    const filter = { ...appliedFilter.value }
    const exported: InventoryItem[] = []
    let exportPage = 0
    let expected = 0
    do {
      const result = await getInventory({ ...filter, page: exportPage, size: 100 })
      exported.push(...result.items)
      expected = result.total
      exportPage += 1
      if (result.items.length === 0) break
    } while (exported.length < expected)
    downloadInventoryCsv(exported)
  } catch (cause) {
    alert.value = errorMessage(cause)
  } finally {
    exportLoading.value = false
  }
}

async function toggleLowStock() {
  page.value = 0
  await load(activeFilter())
}

async function adjustmentSucceeded() {
  adjusting.value = null
  await load()
}

async function changePage(nextPage: number) {
  if (nextPage < 0 || nextPage >= totalPages.value || nextPage === page.value) return
  page.value = nextPage
  await load()
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
        <form class="search" role="search" @submit.prevent="search">
          <label>搜索字段
            <select v-model="searchField" data-testid="inventory-search-field">
              <option value="name">商品名称</option>
              <option value="skuCode">SKU 编码</option>
              <option value="barcode">条码</option>
            </select>
          </label>
          <label>搜索内容<input v-model="keyword" data-testid="inventory-search-value" placeholder="输入搜索内容"></label>
          <label class="check"><input v-model="lowStockOnly" data-testid="low-stock-only" type="checkbox" @change="toggleLowStock">仅看低库存</label>
          <button type="submit" data-testid="inventory-search-submit">查询</button>
          <button type="button" class="secondary" :disabled="total === 0 || exportLoading" @click="exportCurrent">{{ exportLoading ? '导出中…' : '导出筛选结果' }}</button>
        </form>
      </div>
      <p v-if="loading">正在加载…</p>
      <div v-else class="table-wrap">
        <table><thead><tr><th>商品</th><th>SKU 编码</th><th>条码</th><th>分类 / 品牌</th><th>数量</th><th>平均成本</th><th>库存金额</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in items" :key="item.skuId">
              <td>{{ item.productName }}</td><td>{{ item.skuCode }}</td><td>{{ item.barcode }}</td><td>{{ item.categoryName }} / {{ item.brandName }}</td>
              <td>{{ item.quantity }}</td><td>{{ Number(item.averageCost).toFixed(4) }}</td><td>¥{{ Number(item.inventoryValue).toFixed(2) }}</td>
              <td><div class="row-actions"><button type="button" class="link" @click="showMovements(item)">查看流水</button><button type="button" class="link" :data-testid="`open-adjustment-${item.skuId}`" @click="adjusting = item">库存调整</button></div></td>
            </tr>
            <tr v-if="items.length === 0"><td colspan="8" class="empty">暂无库存记录</td></tr>
          </tbody>
        </table>
      </div>
      <nav class="pagination" aria-label="库存分页">
        <span>第 {{ page + 1 }} / {{ totalPages }} 页，共 {{ total }} 条</span>
        <div>
          <button type="button" :disabled="page === 0" @click="changePage(page - 1)">上一页</button>
          <button type="button" data-testid="inventory-next-page" :disabled="page + 1 >= totalPages" @click="changePage(page + 1)">下一页</button>
        </div>
      </nav>
    </section>

    <div v-if="selected" class="overlay" role="dialog" aria-modal="true" aria-labelledby="movement-title" @keydown.esc="closeMovements">
      <section class="dialog">
        <header class="section-heading"><div><h2 id="movement-title">库存流水</h2><p>{{ selected.productName }} · {{ selected.skuCode }}</p></div><button type="button" class="close" aria-label="关闭" @click="closeMovements">×</button></header>
        <div class="table-wrap"><table><thead><tr><th>发生时间</th><th>类型</th><th>来源单号</th><th>变动数量</th><th>变动前</th><th>变动后</th><th>单位成本</th></tr></thead>
          <tbody><tr v-for="movement in movements" :key="movement.id"><td>{{ formatBusinessDateTime(movement.occurredAt) }}</td><td>{{ movement.movementType }}</td><td>{{ movement.documentNo }}</td><td>{{ signedQuantity(movement.quantityDelta) }}</td><td>{{ movement.quantityBefore }}</td><td>{{ movement.quantityAfter }}</td><td>{{ Number(movement.unitCost).toFixed(4) }}</td></tr></tbody>
        </table></div>
      </section>
    </div>
    <AdjustmentDialog v-if="adjusting" :open="true" :item="adjusting" @close="adjusting = null" @success="adjustmentSucceeded" />
  </main>
</template>

<style scoped>
.page { max-width: 82rem; margin: 0 auto; display: grid; gap: 1rem; color: #0f172a; } h1, h2, p { margin-top: 0; }.eyebrow { color: #2563eb; font-size: .78rem; font-weight: 800; margin-bottom: .3rem; }
.card, .dialog { background: white; border: 1px solid #e2e8f0; border-radius: .75rem; padding: 1.2rem; }.section-heading, .search { display: flex; align-items: end; justify-content: space-between; gap: .75rem; }.section-heading p { margin-bottom: 0; color: #64748b; }
label { display: grid; gap: .3rem; color: #334155; font-size: .88rem; } input, select { font: inherit; border: 1px solid #cbd5e1; border-radius: .4rem; padding: .6rem; background: white; } button { font: inherit; border: 0; border-radius: .4rem; padding: .65rem .9rem; color: white; background: #2563eb; cursor: pointer; } button:disabled { cursor: not-allowed; opacity: .45; }.link, .close { background: none; color: #2563eb; padding: .2rem; }.close { color: #334155; font-size: 1.5rem; }
.check { display: flex; align-items: center; white-space: nowrap; padding-bottom: .6rem; }.check input { width: 1rem; height: 1rem; }.secondary { background: #475569; white-space: nowrap; }.row-actions { display: flex; gap: .7rem; }
.table-wrap { overflow-x: auto; margin-top: 1rem; } table { width: 100%; border-collapse: collapse; } th, td { padding: .7rem; text-align: left; border-top: 1px solid #e2e8f0; white-space: nowrap; } th { color: #475569; font-size: .82rem; }.empty { color: #94a3b8; text-align: center; }.alert { color: #b91c1c; }
.pagination { display: flex; justify-content: space-between; align-items: center; gap: .75rem; margin-top: 1rem; color: #475569; }.pagination div { display: flex; gap: .5rem; }
.overlay { position: fixed; inset: 0; z-index: 20; background: rgb(15 23 42 / .5); display: grid; place-items: center; padding: 1rem; }.dialog { width: min(70rem, 100%); max-height: 90vh; overflow: auto; }
@media (max-width: 750px) { .section-heading { align-items: stretch; flex-direction: column; }.search { align-items: stretch; flex-direction: column; } }
</style>
