<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorMessage } from '../../catalog/api'
import { getDashboard } from '../api'
import type { DashboardView } from '../api'
import { formatBusinessDateTime } from '@/shared/format/dateTime'
import { formatCurrency } from '../../reports/currency'

const data=ref<DashboardView|null>(null);const loading=ref(false);const alert=ref('')
onMounted(load)
async function load(){loading.value=true;alert.value='';try{data.value=await getDashboard(undefined)}catch(cause){alert.value=errorMessage(cause)}finally{loading.value=false}}
const documentLabel=(type:string)=>type==='SALE'?'销售':'入库'
</script>

<template><main class="page"><header><p class="eyebrow">门店概览</p><h1>今日经营看板</h1><p v-if="data">营业日期 {{data.date}}</p></header><p v-if="alert" role="alert" class="alert">{{alert}}</p><p v-if="loading">正在加载…</p>
  <template v-else-if="data"><section class="metrics"><article><span>今日销售额</span><strong>{{formatCurrency(data.salesAmount)}}</strong><small>{{data.salesOrderCount}} 笔销售</small></article><article><span>今日毛利</span><strong>{{formatCurrency(data.grossProfit)}}</strong><small>按销售与退货成本快照计算</small></article><article><span>今日进货</span><strong>{{formatCurrency(data.inboundAmount)}}</strong><small>{{data.inboundQuantity}} 件</small></article><article><span>当前库存成本</span><strong>{{formatCurrency(data.inventoryValue)}}</strong><small>{{data.inventoryQuantity}} 件</small></article></section>
  <section :class="['warning',data.lowStockCount?'active':'']"><strong>低库存商品 {{data.lowStockCount}} 种</strong><span>{{data.lowStockCount?'请及时查看库存报表并安排补货。':'当前没有需要预警的商品。'}}</span></section>
  <section class="grid"><article class="card"><h2>畅销商品</h2><table><thead><tr><th>商品</th><th>SKU</th><th>净销量</th><th>净销售额</th></tr></thead><tbody><tr v-for="item in data.topProducts" :key="item.skuId"><td>{{item.productName}}</td><td>{{item.skuCode}}</td><td>{{item.netQuantity}}</td><td>{{formatCurrency(item.netSalesAmount)}}</td></tr><tr v-if="!data.topProducts.length"><td colspan="4" class="empty">今日暂无销售</td></tr></tbody></table></article>
  <article class="card"><h2>最近单据</h2><ul><li v-for="document in data.recentDocuments" :key="document.documentType+document.id"><span><b>{{documentLabel(document.documentType)}}</b> {{document.orderNo}}<small>{{formatBusinessDateTime(document.occurredAt)}}</small></span><strong>{{formatCurrency(document.amount)}}</strong></li><li v-if="!data.recentDocuments.length" class="empty">今日暂无单据</li></ul></article></section></template></main></template>

<style scoped>.page{max-width:82rem;margin:0 auto;display:grid;gap:1rem;color:#0f172a}h1,h2,p{margin-top:0}.eyebrow{color:#2563eb;font-size:.78rem;font-weight:800;margin-bottom:.3rem}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:.8rem}.metrics article,.card,.warning{background:#fff;border:1px solid #e2e8f0;border-radius:.75rem;padding:1rem}.metrics span,.metrics small{display:block;color:#64748b}.metrics strong{display:block;font-size:1.65rem;margin:.4rem 0}.warning{display:flex;justify-content:space-between;gap:1rem}.warning.active{border-color:#f59e0b;background:#fffbeb;color:#92400e}.grid{display:grid;grid-template-columns:1.2fr 1fr;gap:1rem}table{width:100%;border-collapse:collapse}th,td{padding:.7rem;text-align:left;border-top:1px solid #e2e8f0}th{font-size:.82rem;color:#64748b}ul{list-style:none;padding:0;margin:0}li{display:flex;justify-content:space-between;gap:1rem;padding:.75rem 0;border-top:1px solid #e2e8f0}li small{display:block;color:#64748b;margin-top:.2rem}.empty{color:#94a3b8;text-align:center}.alert{color:#b91c1c}@media(max-width:850px){.metrics{grid-template-columns:repeat(2,1fr)}.grid{grid-template-columns:1fr}}@media(max-width:520px){.metrics{grid-template-columns:1fr}.warning{flex-direction:column}}</style>
