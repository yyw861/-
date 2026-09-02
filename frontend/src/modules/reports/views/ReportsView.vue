<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorMessage } from '../../catalog/api'
import { getCategoryShare, getInboundSummary, getInventoryValuation, getLowStock, getProductRanking, getSalesSummary } from '../api'
import CategoryShareChart from '../components/CategoryShareChart.vue'
import DateRangeFilter from '../components/DateRangeFilter.vue'
import SalesTrendChart from '../components/SalesTrendChart.vue'
import { downloadRankingCsv } from '../exportReportsCsv'
import { formatCurrency } from '../currency'
import type { CategoryShare, InboundSummary, InventoryValuation, LowStockItem, ProductRanking, SalesSummary } from '../types'

type RangeMode = 'day' | 'month' | 'custom'
const zeroSales: SalesSummary = { grossSalesAmount:'0.00',refundAmount:'0.00',netSalesAmount:'0.00',grossProfit:'0.00',orderCount:0,netQuantity:0,trend:[] }
const zeroInbound: InboundSummary = { orderCount:0,totalQuantity:0,totalAmount:'0.00' }
const zeroInventory: InventoryValuation = { skuCount:0,totalQuantity:0,totalCost:'0.0000' }
const current = businessToday()
const mode = ref<RangeMode>('day')
const fromDate = ref(current)
const toDate = ref(current)
const appliedFrom = ref(current)
const appliedTo = ref(current)
const sales = ref<SalesSummary>(zeroSales)
const inbound = ref<InboundSummary>(zeroInbound)
const inventory = ref<InventoryValuation>(zeroInventory)
const ranking = ref<ProductRanking[]>([])
const categories = ref<CategoryShare[]>([])
const majorCategories = ref<CategoryShare[]>([])
const selectedCategoryId = ref('')
const lowStock = ref<LowStockItem[]>([])
const loading = ref(false)
const alert = ref('')
let requestSequence = 0

onMounted(load)

function businessToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit' }).format(new Date())
}

function monthBounds(date: string) {
  const [year, month] = date.split('-').map(Number) as [number, number, number]
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return { from:`${year}-${String(month).padStart(2,'0')}-01`, to:`${year}-${String(month).padStart(2,'0')}-${last}` }
}

function inclusiveDays(from: string, to: string) {
  const start = Date.parse(`${from}T00:00:00Z`); const end = Date.parse(`${to}T00:00:00Z`)
  return Math.floor((end-start)/86_400_000)+1
}

async function changeMode(value: RangeMode) {
  mode.value = value
  alert.value = ''
  if (value === 'custom') return
  if (value === 'day') { fromDate.value=current; toDate.value=current }
  else { const range=monthBounds(current); fromDate.value=range.from; toDate.value=range.to }
  await applyRange()
}

async function applyRange() {
  if (!fromDate.value || !toDate.value || fromDate.value > toDate.value) { alert.value='请选择有效的开始和结束日期'; return }
  if (inclusiveDays(fromDate.value,toDate.value)>366) { alert.value='查询范围不能超过 366 天'; return }
  await load(fromDate.value, toDate.value)
}

async function load(candidateFrom = appliedFrom.value, candidateTo = appliedTo.value) {
  const request = ++requestSequence
  loading.value=true; alert.value=''
  try {
    const majorRequest = getCategoryShare(candidateFrom,candidateTo)
    const scopedRequest = selectedCategoryId.value
      ? getCategoryShare(candidateFrom,candidateTo,selectedCategoryId.value) : majorRequest
    const [salesValue,rankingValue,majorCategoryValue,categoryValue,inboundValue,inventoryValue,lowStockValue] = await Promise.all([
      getSalesSummary(candidateFrom,candidateTo), getProductRanking(candidateFrom,candidateTo),
      majorRequest, scopedRequest, getInboundSummary(candidateFrom,candidateTo),
      getInventoryValuation(), getLowStock(),
    ])
    if (request !== requestSequence) return
    sales.value=salesValue;ranking.value=rankingValue;majorCategories.value=majorCategoryValue;categories.value=categoryValue
    inbound.value=inboundValue;inventory.value=inventoryValue;lowStock.value=lowStockValue
    appliedFrom.value=candidateFrom;appliedTo.value=candidateTo
  } catch(cause) { if(request===requestSequence) alert.value=errorMessage(cause) }
  finally { if(request===requestSequence) loading.value=false }
}

async function changeCategoryScope() {
  await load(appliedFrom.value, appliedTo.value)
}
</script>

<template>
  <main class="page">
    <header class="hero"><div><p class="eyebrow">经营分析</p><h1>统计报表</h1><p>销售、毛利、进货和库存数据统一按门店营业日期统计。</p></div><DateRangeFilter v-model:from-date="fromDate" v-model:to-date="toDate" :mode="mode" @mode="changeMode" @apply="applyRange" /></header>
    <p v-if="alert" role="alert" class="alert">{{alert}}</p><p v-if="loading" class="loading">正在汇总数据…</p>
    <template v-else>
      <section class="metrics" aria-label="报表指标"><article><span>净销售额</span><strong>{{formatCurrency(sales.netSalesAmount)}}</strong><small>退款 {{formatCurrency(sales.refundAmount)}}</small></article><article><span>销售毛利</span><strong>{{formatCurrency(sales.grossProfit)}}</strong><small>{{sales.orderCount}} 笔销售 · 净售 {{sales.netQuantity}} 件</small></article><article><span>进货金额</span><strong>{{formatCurrency(inbound.totalAmount)}}</strong><small>{{inbound.orderCount}} 单 · {{inbound.totalQuantity}} 件</small></article><article><span>库存成本</span><strong>{{formatCurrency(inventory.totalCost)}}</strong><small>{{inventory.skuCount}} 个 SKU · {{inventory.totalQuantity}} 件</small></article></section>
      <section class="chart-grid"><article class="card"><h2>销售与毛利趋势</h2><SalesTrendChart v-if="sales.trend.length" :points="sales.trend"/><p v-else class="empty">暂无销售趋势</p></article><article class="card"><div class="section-heading"><h2>{{ selectedCategoryId ? '小类销售占比' : '大类销售占比' }}</h2><label>查看层级<select v-model="selectedCategoryId" data-testid="category-share-drilldown" @change="changeCategoryScope"><option value="">全部大类</option><option v-for="item in majorCategories" :key="item.categoryId" :value="item.categoryId">{{item.categoryCode}} {{item.categoryName}}</option></select></label></div><CategoryShareChart v-if="categories.length" :items="categories"/><p v-else class="empty">暂无分类销售数据</p></article></section>
      <section class="card"><div class="section-heading"><div><h2>商品销量排行</h2><p>{{appliedFrom}} 至 {{appliedTo}}，按净销量排序。</p></div><button data-testid="export-ranking" type="button" :disabled="!ranking.length" @click="downloadRankingCsv(ranking,appliedFrom,appliedTo)">导出排行</button></div><div class="table-wrap"><table><thead><tr><th>商品</th><th>大类</th><th>小类</th><th>SKU</th><th>条码</th><th>销售</th><th>退货</th><th>净销量</th><th>净销售额</th></tr></thead><tbody><tr v-for="item in ranking" :key="item.skuId"><td>{{item.productName}}</td><td>{{item.categoryCode}} {{item.categoryName}}</td><td>{{item.subCategoryCode}} {{item.subCategoryName}}</td><td>{{item.skuCode}}</td><td>{{item.barcode}}</td><td>{{item.grossQuantity}}</td><td>{{item.returnedQuantity}}</td><td>{{item.netQuantity}}</td><td>{{formatCurrency(item.netSalesAmount)}}</td></tr><tr v-if="!ranking.length"><td colspan="9" class="empty">暂无商品排行</td></tr></tbody></table></div></section>
      <section class="card"><h2>低库存商品</h2><div class="table-wrap"><table><thead><tr><th>商品</th><th>大类</th><th>小类</th><th>SKU</th><th>条码</th><th>当前库存</th><th>预警库存</th></tr></thead><tbody><tr v-for="item in lowStock" :key="item.skuId"><td>{{item.productName}}</td><td>{{item.categoryCode}} {{item.categoryName}}</td><td>{{item.subCategoryCode}} {{item.subCategoryName}}</td><td>{{item.skuCode}}</td><td>{{item.barcode}}</td><td>{{item.quantity}}</td><td>{{item.warningStock}}</td></tr><tr v-if="!lowStock.length"><td colspan="7" class="empty">暂无低库存商品</td></tr></tbody></table></div></section>
    </template>
  </main>
</template>

<style scoped>.page{max-width:82rem;margin:0 auto;display:grid;gap:1rem;color:#0f172a}.hero,.section-heading{display:flex;align-items:end;justify-content:space-between;gap:1rem}.hero h1,.hero p,h2{margin-top:0}.eyebrow{color:#2563eb;font-size:.78rem;font-weight:800;margin-bottom:.3rem}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:.8rem}.metrics article,.card{background:white;border:1px solid #e2e8f0;border-radius:.75rem;padding:1rem}.metrics span,.metrics small{display:block;color:#64748b}.metrics strong{display:block;font-size:1.7rem;margin:.4rem 0}.chart-grid{display:grid;grid-template-columns:1.5fr 1fr;gap:1rem}.section-heading p{margin:0;color:#64748b}button{font:inherit;border:0;border-radius:.4rem;padding:.65rem .9rem;background:#2563eb;color:white;cursor:pointer}button:disabled{opacity:.45}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;margin-top:.8rem}th,td{padding:.7rem;text-align:left;border-top:1px solid #e2e8f0;white-space:nowrap}th{font-size:.82rem;color:#475569}.empty{color:#94a3b8;text-align:center;padding:2rem}.alert{color:#b91c1c}.loading{color:#64748b}@media(max-width:900px){.metrics{grid-template-columns:repeat(2,1fr)}.chart-grid{grid-template-columns:1fr}.hero{align-items:stretch;flex-direction:column}}@media(max-width:560px){.metrics{grid-template-columns:1fr}}</style>
