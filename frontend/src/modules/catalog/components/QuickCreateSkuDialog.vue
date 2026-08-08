<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'

import { errorMessage, quickCreateSku } from '../api'
import type { Brand, Category, Product, Sku } from '../types'
import { isNonNegativeInteger, isNonNegativeMoney } from '@/shared/validation/numbers'

const props = defineProps<{
  open: boolean
  barcode: string
  category: Category | null
  brands: Brand[]
  products: Product[]
}>()
const emit = defineEmits<{ close: []; created: [sku: Sku, productName: string] }>()

const form = reactive({ existingSpuId: '', productName: '', brandId: '', skuCode: '', specsText: '', retailPrice: '', warningStock: '0' })
const saving = ref(false)
const error = ref('')
const productNameInput = ref<HTMLInputElement>()
const eligibleProducts = computed(() => props.products.filter((product) => product.categoryId === props.category?.id && product.enabled))

watch(() => [props.open, props.barcode], async () => {
  if (!props.open) return
  Object.assign(form, { existingSpuId: '', productName: '', brandId: props.brands.find((brand) => brand.enabled)?.id ?? '', skuCode: '', specsText: '', retailPrice: '', warningStock: '0' })
  error.value = ''
  await nextTick()
  productNameInput.value?.focus()
})

watch(() => form.existingSpuId, (id) => {
  if (!id) return
  const product = eligibleProducts.value.find((item) => item.id === id)
  if (product) {
    form.productName = product.name
    form.brandId = product.brandId
  }
})

function parseSpecs(text: string): Record<string, string> {
  if (!text.trim()) return {}
  return Object.fromEntries(text.split(',').map((part) => {
    const [name, ...value] = part.split(':')
    if (!name?.trim() || !value.join(':').trim()) throw new Error('规格格式应为“名称:值”，多项用英文逗号分隔')
    return [name.trim(), value.join(':').trim()]
  }))
}

async function save() {
  if (saving.value) return
  if (!props.category) return
  error.value = ''
  if (!form.productName.trim() || !form.skuCode.trim() || !form.brandId || form.retailPrice === '') {
    error.value = '商品名称、品牌、SKU 编码和零售价为必填项'
    return
  }
  if (!isNonNegativeMoney(form.retailPrice)) {
    error.value = '零售价最多保留 2 位小数，且不得小于 0'
    return
  }
  if (!isNonNegativeInteger(form.warningStock)) {
    error.value = '库存预警值必须是非负整数'
    return
  }
  saving.value = true
  try {
    const sku = await quickCreateSku({
      categoryId: props.category.id,
      brandId: form.brandId,
      existingSpuId: form.existingSpuId || null,
      productName: form.productName.trim(),
      skuCode: form.skuCode.trim(),
      barcode: props.barcode,
      specs: parseSpecs(form.specsText),
      retailPrice: Number(form.retailPrice),
      warningStock: Number(form.warningStock),
    })
    emit('created', sku, form.productName.trim())
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="open" data-testid="quick-create-dialog" class="overlay" role="dialog" aria-modal="true" aria-labelledby="quick-title">
    <form class="dialog" @submit.prevent="save" @keydown.esc="$emit('close')">
      <header><div><p class="eyebrow">未知条码</p><h2 id="quick-title">快速建档</h2></div><button data-testid="quick-close" type="button" class="close" aria-label="关闭" @click="$emit('close')">×</button></header>
      <p v-if="error" role="alert" class="alert">{{ error }}</p>
      <div class="grid">
        <label>商品分类<input data-testid="quick-category" :value="category?.name" disabled></label>
        <label>商品条码<input data-testid="quick-barcode" :value="barcode" disabled></label>
        <label>关联已有商品（可选）
          <select v-model="form.existingSpuId"><option value="">新建商品</option><option v-for="product in eligibleProducts" :key="product.id" :value="product.id">{{ product.name }}</option></select>
        </label>
        <label>商品名称<input ref="productNameInput" v-model="form.productName" data-testid="quick-product-name" required :disabled="Boolean(form.existingSpuId)"></label>
        <label>品牌<select v-model="form.brandId" required :disabled="Boolean(form.existingSpuId)"><option value="">请选择</option><option v-for="brand in brands.filter((item) => item.enabled)" :key="brand.id" :value="brand.id">{{ brand.name }}</option></select></label>
        <label>SKU 编码<input v-model="form.skuCode" data-testid="quick-sku-code" required></label>
        <label>规格<input v-model="form.specsText" placeholder="颜色:红色,尺码:42"></label>
        <label>零售价<input v-model="form.retailPrice" data-testid="quick-retail-price" type="number" min="0" step="0.01" required></label>
        <label>库存预警值<input v-model="form.warningStock" data-testid="quick-warning-stock" type="number" min="0" step="1"></label>
      </div>
      <footer><button type="button" class="secondary" @click="$emit('close')">取消</button><button data-testid="quick-save" type="button" :disabled="saving" @click="save">{{ saving ? '创建中…' : '创建并回填' }}</button></footer>
    </form>
  </div>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; z-index: 20; background: rgb(15 23 42 / .5); display: grid; place-items: center; padding: 1rem; }
.dialog { width: min(48rem, 100%); background: white; border-radius: .8rem; padding: 1.25rem; box-shadow: 0 25px 50px rgb(15 23 42 / .25); }
header, footer { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
h2, .eyebrow { margin: 0; }.eyebrow { color: #2563eb; font-size: .8rem; font-weight: 700; }
.close { border: 0; background: none; font-size: 1.6rem; }.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin: 1rem 0; }
label { display: grid; gap: .35rem; font-size: .9rem; color: #334155; } input, select { font: inherit; padding: .65rem; border: 1px solid #cbd5e1; border-radius: .4rem; }
footer { justify-content: flex-end; }.alert { color: #b91c1c; }.secondary { background: white; color: #334155; }
@media (max-width: 600px) { .grid { grid-template-columns: 1fr; } }
</style>
