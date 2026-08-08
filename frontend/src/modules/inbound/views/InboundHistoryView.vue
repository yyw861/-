<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'

import { errorMessage } from '../../catalog/api'
import { getInboundDetail, getInboundHistory } from '../api'
import type { InboundReceipt, InboundSummary } from '../types'

const filters = reactive({ fromDate: '', toDate: '', orderNo: '' })
const rows = ref<InboundSummary[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const alert = ref('')
const detail = ref<InboundReceipt | null>(null)
const detailCloseButton = ref<HTMLButtonElement>()
const detailTrigger = ref<HTMLElement | null>(null)

onMounted(search)

async function search(reset = true) {
  if (filters.fromDate && filters.toDate && filters.fromDate > filters.toDate) {
    alert.value = '开始日期不能晚于结束日期'
    return
  }
  if (reset) page.value = 0
  loading.value = true
  alert.value = ''
  try {
    const result = await getInboundHistory({ ...filters, page: page.value, size: 20 })
    rows.value = result.items
    total.value = result.total
  } catch (cause) { alert.value = errorMessage(cause) }
  finally { loading.value = false }
}

async function openDetail(id: string, event?: MouseEvent) {
  alert.value = ''
  detailTrigger.value = event?.currentTarget as HTMLElement | null
  try {
    detail.value = await getInboundDetail(id)
    await nextTick()
    detailCloseButton.value?.focus()
  }
  catch (cause) { alert.value = errorMessage(cause) }
}

async function closeDetail() {
  detail.value = null
  await nextTick()
  detailTrigger.value?.focus()
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <main class="page">
    <header class="page-header"><div><p class="eyebrow">进货管理</p><h1>入库历史</h1><p>按门店本地日期或入库单号查询已确认单据。</p></div><RouterLink to="/inbounds">返回扫码入库</RouterLink></header>
    <section class="card">
      <form class="filters" aria-label="入库历史筛选" @submit.prevent="search(true)">
        <label>开始日期<input v-model="filters.fromDate" data-testid="from-date" type="date"></label>
        <label>结束日期<input v-model="filters.toDate" data-testid="to-date" type="date"></label>
        <label>入库单号<input v-model.trim="filters.orderNo" data-testid="order-no" placeholder="支持部分单号"></label>
        <button data-testid="history-search" type="button" :disabled="loading" @click="search(true)">{{ loading ? '查询中…' : '查询' }}</button>
      </form>
      <p v-if="alert" role="alert" class="alert">{{ alert }}</p>
    </section>
    <section class="card">
      <div class="section-heading"><h2>入库单据</h2><span>共 {{ total }} 张</span></div>
      <div class="table-wrap"><table><thead><tr><th>入库单号</th><th>入库时间</th><th>总数量</th><th>总金额</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.orderNo }}</td><td>{{ formatTime(row.occurredAt) }}</td><td>{{ row.totalQuantity }}</td><td>¥{{ Number(row.totalAmount).toFixed(2) }}</td><td>已确认</td><td><button :data-testid="`detail-${row.id}`" type="button" class="link" @click="openDetail(row.id, $event)">查看详情</button></td></tr><tr v-if="!loading && rows.length === 0"><td colspan="6" class="empty">暂无符合条件的入库单</td></tr></tbody>
      </table></div>
      <div class="pagination"><button type="button" class="secondary" :disabled="page === 0" @click="page--; search(false)">上一页</button><span>第 {{ page + 1 }} 页</span><button type="button" class="secondary" :disabled="(page + 1) * 20 >= total" @click="page++; search(false)">下一页</button></div>
    </section>

    <div v-if="detail" class="overlay" role="dialog" aria-modal="true" aria-labelledby="detail-title">
      <section data-testid="inbound-detail" class="dialog" @keydown.esc="closeDetail">
        <header class="section-heading"><div><p class="eyebrow">已确认入库单</p><h2 id="detail-title">{{ detail.orderNo }}</h2></div><button ref="detailCloseButton" data-testid="inbound-detail-close" type="button" class="close" aria-label="关闭" @click="closeDetail">×</button></header>
        <dl><div><dt>入库时间</dt><dd>{{ formatTime(detail.occurredAt) }}</dd></div><div><dt>总数量</dt><dd>{{ detail.totalQuantity }}</dd></div><div><dt>总金额</dt><dd>¥{{ Number(detail.totalAmount).toFixed(2) }}</dd></div><div><dt>备注</dt><dd>{{ detail.remark || '—' }}</dd></div></dl>
        <div class="table-wrap"><table><thead><tr><th>商品</th><th>SKU / 条码</th><th>数量</th><th>进价</th><th>小计</th></tr></thead><tbody><tr v-for="line in detail.lines" :key="line.id"><td>{{ line.productName }}</td><td>{{ line.skuCode }}<small>{{ line.barcode }}</small></td><td>{{ line.quantity }}</td><td>¥{{ Number(line.unitCost).toFixed(2) }}</td><td>¥{{ Number(line.subtotal).toFixed(2) }}</td></tr></tbody></table></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.page { max-width: 78rem; margin: 0 auto; display: grid; gap: 1rem; color: #0f172a; }.page-header, .section-heading, .pagination { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }h1,h2,p { margin-top: 0; }.eyebrow { margin-bottom: .3rem; color: #2563eb; font-weight: 800; font-size: .78rem; }
.card { padding: 1.2rem; background: white; border: 1px solid #e2e8f0; border-radius: .75rem; }.filters { display: grid; grid-template-columns: repeat(3, 1fr) auto; align-items: end; gap: .8rem; }label { display: grid; gap: .35rem; }input { font: inherit; padding: .65rem; border: 1px solid #cbd5e1; border-radius: .4rem; }button { font: inherit; padding: .65rem .9rem; border: 0; border-radius: .4rem; background: #2563eb; color: white; }.link,.close { color: #2563eb; background: none; padding: .2rem; }.secondary { background: #e2e8f0; color: #1e293b; }.alert { color: #b91c1c; }
.table-wrap { overflow-x: auto; }table { width: 100%; border-collapse: collapse; }th,td { text-align: left; padding: .7rem; border-bottom: 1px solid #e2e8f0; }th { color: #475569; font-size: .84rem; }small { display: block; color: #64748b; }.empty { text-align: center; color: #94a3b8; }.pagination { justify-content: flex-end; margin-top: 1rem; }
.overlay { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 1rem; background: rgb(15 23 42 / .5); }.dialog { width: min(62rem, 100%); max-height: 90vh; overflow: auto; padding: 1.2rem; border-radius: .8rem; background: white; }.close { color: #334155; font-size: 1.5rem; }dl { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; }dt { color: #64748b; font-size: .8rem; }dd { margin: .25rem 0 0; }
@media (max-width: 700px) { .filters, dl { grid-template-columns: 1fr; }.page-header { align-items: stretch; flex-direction: column; } }
</style>
