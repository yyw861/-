<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { errorMessage, findCategoryByPrefix, findSkuByBarcode, getBrands, getProduct, getProducts, getSubCategories, isNotFound } from '../../catalog/api'
import type { Brand, Category, Product, Sku, SubCategory } from '../../catalog/types'
import QuickCreateSkuDialog from '../../catalog/components/QuickCreateSkuDialog.vue'
import BarcodeInput from '../components/BarcodeInput.vue'
import InboundDraftTable from '../components/InboundDraftTable.vue'
import { useInboundDraftStore } from '../stores/inboundDraft'

const store = useInboundDraftStore()
const { lines, remark, submitting, lastReceipt, pendingConflict } = storeToRefs(store)
const brands = ref<Brand[]>([])
const products = ref<Product[]>([])
const scanner = ref<InstanceType<typeof BarcodeInput>>()
const pendingQuantityInput = ref<HTMLInputElement>()
const scanning = ref(false)
const alert = ref('')
const quickOpen = ref(false)
const quickBarcode = ref('')
const quickCategory = ref<Category | null>(null)
const quickSubCategories = ref<SubCategory[]>([])
const pending = ref<{ sku: Sku; productName: string } | null>(null)
const quantity = ref('1')
const unitCost = ref('')
const pendingCostInput = ref<HTMLInputElement>()

const totals = computed(() => lines.value.reduce((total, line) => ({
  quantity: total.quantity + line.quantity,
  amount: total.amount + line.quantity * line.unitCost,
}), { quantity: 0, amount: 0 }))

watch(remark, () => store.markChanged())

onMounted(async () => {
  try {
    const [brandData, productItems] = await Promise.all([getBrands(), loadAllProducts()])
    brands.value = brandData
    products.value = productItems
  } catch (cause) {
    alert.value = errorMessage(cause)
  }
})

async function loadAllProducts(): Promise<Product[]> {
  const first = await getProducts(0, 100)
  const items = [...first.items]
  for (let nextPage = 1; items.length < first.total; nextPage += 1) {
    const result = await getProducts(nextPage, 100)
    if (result.items.length === 0) break
    items.push(...result.items)
  }
  return items
}

async function scan(barcode: string) {
  if (scanning.value || pending.value || quickOpen.value || pendingConflict.value) return
  alert.value = ''
  pending.value = null
  if (!/^\d{3,}$/.test(barcode)) {
    alert.value = '条码必须是至少 3 位数字。'
    return
  }
  scanning.value = true
  try {
    const [skuResult, categoryResult] = await Promise.allSettled([
      findSkuByBarcode(barcode), findCategoryByPrefix(barcode.slice(0, 2)),
    ])
    if (categoryResult.status === 'rejected') {
      alert.value = isNotFound(categoryResult.reason)
        ? `未找到编号为 ${barcode.slice(0, 2)} 的大类，请先到商品管理建立对应大类。`
        : errorMessage(categoryResult.reason)
      return
    }
    const scannedCategory = categoryResult.value
    if (skuResult.status === 'rejected') {
      if (!isNotFound(skuResult.reason)) {
        alert.value = errorMessage(skuResult.reason)
        return
      }
      const subCategories = await getSubCategories(scannedCategory.id)
      if (!subCategories.some((item) => item.enabled)) {
        alert.value = `大类“${scannedCategory.name}”下没有可用小类，请先建立小类。`
        return
      }
      quickBarcode.value = barcode
      quickCategory.value = scannedCategory
      quickSubCategories.value = subCategories
      quickOpen.value = true
      return
    }
    const sku = skuResult.value
    const product = await getProduct(sku.spuId)
    if (!sku.enabled || !product.enabled) {
      alert.value = '该商品或 SKU 已停用，不能入库。'
      return
    }
    pending.value = { sku, productName: product.name }
    quantity.value = '1'
    unitCost.value = ''
  } catch (cause) { alert.value = errorMessage(cause) } finally {
    scanning.value = false
    await nextTick()
    if (pending.value) pendingQuantityInput.value?.focus()
    else if (!quickOpen.value && !pendingConflict.value) await scanner.value?.focus()
  }
}

async function quickCreated(sku: Sku, productName: string) {
  quickOpen.value = false
  quickCategory.value = null
  quickSubCategories.value = []
  pending.value = { sku, productName }
  quantity.value = '1'
  unitCost.value = ''
  void refreshProducts()
  await nextTick()
  pendingQuantityInput.value?.focus()
}

async function closeQuick() {
  quickOpen.value = false
  quickCategory.value = null
  quickSubCategories.value = []
  await nextTick()
  await scanner.value?.focus()
}

async function refreshProducts() {
  try { products.value = await loadAllProducts() } catch { /* non-blocking refresh */ }
}

async function addPendingLine() {
  alert.value = ''
  if (!pending.value || quantity.value === '' || unitCost.value === '') {
    alert.value = '数量和进价为必填项。'
    return
  }
  try {
    const result = store.addLine({
      ...pending.value,
      quantity: Number(quantity.value),
      unitCost: Number(unitCost.value),
    })
    if (result.kind !== 'price-conflict') {
      pending.value = null
      await nextTick()
      await scanner.value?.focus()
    }
  } catch (cause) {
    alert.value = errorMessage(cause)
  }
}

function focusUnitCost() { pendingCostInput.value?.focus() }

async function resolveConflict(choice: 'update' | 'cancel') {
  try {
    store.resolvePriceConflict(choice)
    pending.value = null
    await nextTick()
    await scanner.value?.focus()
  } catch (cause) {
    alert.value = errorMessage(cause)
  }
}

async function confirm() {
  alert.value = ''
  try { await store.confirm() } catch (cause) { alert.value = errorMessage(cause) }
}
</script>

<template>
  <main class="page">
    <header class="page-header">
      <div><p class="eyebrow">进货管理</p><h1>扫码入库</h1><p>扫描或手动输入条码，系统按前两位自动识别大类。</p></div>
      <RouterLink class="history-link" to="/inbounds/history">查看入库历史</RouterLink>
    </header>

    <section class="card" aria-labelledby="scan-title">
      <div class="section-heading"><div><h2 id="scan-title">1. 扫描条码</h2><p>条码前两位对应大类编号</p></div><span class="scan-status">扫码枪可直接回车</span></div>
      <BarcodeInput ref="scanner" :disabled="quickOpen || Boolean(pending) || Boolean(pendingConflict)" :busy="scanning" @submit="scan" />
      <p v-if="alert" role="alert" class="alert">{{ alert }}</p>

      <form v-if="pending" data-testid="pending-sku" class="pending" @submit.prevent="addPendingLine">
        <div><strong>{{ pending.productName }}</strong><span>{{ pending.sku.skuCode }} · {{ pending.sku.barcode }}</span></div>
        <label>数量<input ref="pendingQuantityInput" v-model="quantity" data-testid="pending-quantity" type="number" min="1" max="2147483647" step="1" required @keydown.enter.prevent="focusUnitCost"></label>
        <label>进价<input ref="pendingCostInput" v-model="unitCost" data-testid="pending-unit-cost" type="number" min="0" step="0.01" required></label>
        <button data-testid="add-line" type="submit">加入清单</button>
      </form>
    </section>

    <section class="card" aria-labelledby="draft-title">
      <div class="section-heading"><div><h2 id="draft-title">2. 核对入库清单</h2><p>共 {{ totals.quantity }} 件，金额 ¥{{ totals.amount.toFixed(2) }}</p></div></div>
      <InboundDraftTable :lines="lines" @remove="store.removeLine" />
      <label class="remark">整单备注<textarea v-model="remark" data-testid="inbound-remark" rows="2" maxlength="500" placeholder="选填"></textarea></label>
      <div class="submit-row">
        <p v-if="lastReceipt" class="success" role="status">入库成功，单号：<strong>{{ lastReceipt.orderNo }}</strong></p>
        <button data-testid="confirm-inbound" type="button" :disabled="submitting || lines.length === 0" @click="confirm">{{ submitting ? '提交中…' : '确认入库' }}</button>
      </div>
    </section>

    <QuickCreateSkuDialog :open="quickOpen" :barcode="quickBarcode" :category="quickCategory" :sub-categories="quickSubCategories" :brands="brands" :products="products" @close="closeQuick" @created="quickCreated" />

    <div v-if="pendingConflict" class="overlay" role="dialog" aria-modal="true" aria-labelledby="cost-conflict-title">
      <section class="conflict" @keydown.esc="resolveConflict('cancel')">
        <h2 id="cost-conflict-title">同一 SKU 的进价不同</h2>
        <p>现有进价 ¥{{ lines[pendingConflict.existingIndex]?.unitCost.toFixed(2) }}，本次进价 ¥{{ pendingConflict.input.unitCost.toFixed(2) }}。请选择处理方式：</p>
        <div><button type="button" autofocus @click="resolveConflict('update')">替换进价并累加数量</button><button type="button" class="secondary" @click="resolveConflict('cancel')">取消本次添加</button></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.page { display: grid; gap: 1rem; max-width: 78rem; margin: 0 auto; color: #0f172a; }
.page-header, .section-heading, .submit-row, .category-card { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
h1, h2, p { margin-top: 0; } h1 { margin-bottom: .35rem; }.eyebrow { margin-bottom: .3rem; color: #2563eb; font-size: .78rem; font-weight: 800; text-transform: uppercase; }
.card { background: white; border: 1px solid #e2e8f0; border-radius: .75rem; padding: 1.2rem; box-shadow: 0 4px 18px rgb(15 23 42 / .04); }
.card h2 { font-size: 1.05rem; margin-bottom: .25rem; }.card p { color: #64748b; margin-bottom: 0; }.history-link { color: #2563eb; }
label { display: grid; gap: .35rem; font-size: .88rem; color: #334155; } input, select, textarea { font: inherit; border: 1px solid #cbd5e1; border-radius: .45rem; padding: .65rem; }
button { font: inherit; border: 0; border-radius: .45rem; padding: .7rem 1rem; background: #2563eb; color: white; cursor: pointer; } button:disabled { opacity: .45; cursor: not-allowed; }.secondary { background: #e2e8f0; color: #1e293b; }
.scan-status { color: #475569; font-size: .82rem; }.alert { margin-top: .75rem !important; color: #b91c1c !important; }.success { color: #166534 !important; }
.pending { display: grid; grid-template-columns: minmax(12rem, 1fr) 8rem 10rem auto; align-items: end; gap: .8rem; background: #eff6ff; margin-top: 1rem; padding: 1rem; border-radius: .6rem; }.pending span { display: block; color: #64748b; margin-top: .25rem; }
.remark { margin-top: 1rem; }.submit-row { margin-top: 1rem; justify-content: flex-end; }.submit-row .success { margin-right: auto; }
.overlay { position: fixed; inset: 0; z-index: 19; background: rgb(15 23 42 / .5); display: grid; place-items: center; padding: 1rem; }.conflict { width: min(30rem, 100%); background: white; border-radius: .75rem; padding: 1.25rem; }.conflict div { display: flex; gap: .75rem; margin-top: 1rem; }
@media (max-width: 750px) { .page-header, .category-card { align-items: stretch; flex-direction: column; }.pending { grid-template-columns: 1fr; } }
</style>
