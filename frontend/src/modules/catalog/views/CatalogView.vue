<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'

import {
  createBrand, createCategory, createProduct, errorMessage, getBrands, getCategories, getProducts,
  quickCreateSku, updateBrand, updateCategory, updateProduct,
} from '../api'
import type { Brand, Category, Product, Sku } from '../types'
import { isJavaInteger, isNonNegativeInteger, isNonNegativeMoney } from '@/shared/validation/numbers'

interface EditableSku extends Omit<Sku, 'specs' | 'retailPrice' | 'warningStock'> {
  specsText: string
  retailPrice: string
  warningStock: string
}

const categories = ref<Category[]>([])
const brands = ref<Brand[]>([])
const products = ref<Product[]>([])
const loading = ref(true)
const saving = ref(false)
const alert = ref('')
const notice = ref('')
const newCategoryName = ref('')
const newBrandName = ref('')
const newProduct = reactive({ productName: '', categoryId: '', brandId: '', imageUrl: '', description: '' })
const editingId = ref('')
const editCloseButton = ref<HTMLButtonElement>()
const editTrigger = ref<HTMLElement | null>(null)
const editProduct = reactive({ productName: '', categoryId: '', brandId: '', imageUrl: '', description: '', enabled: true, skus: [] as EditableSku[] })
const originalProduct = ref<Product | null>(null)
const addingSku = ref(false)
const creatingSku = ref(false)
const newSku = reactive({ skuCode: '', barcode: '', specsText: '', retailPrice: '', warningStock: '0', enabled: true })

onMounted(load)

async function load() {
  loading.value = true
  alert.value = ''
  try {
    const [categoryData, brandData, productItems] = await Promise.all([getCategories(), getBrands(), loadAllProducts()])
    categories.value = categoryData
    brands.value = brandData
    products.value = productItems
  } catch (cause) { alert.value = errorMessage(cause) }
  finally { loading.value = false }
}

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

async function addCategory() {
  if (!newCategoryName.value.trim()) return
  try {
    categories.value.push(await createCategory(newCategoryName.value.trim()))
    newCategoryName.value = ''
  } catch (cause) { alert.value = errorMessage(cause) }
}

async function toggleCategory(category: Category) {
  try { Object.assign(category, await updateCategory(category.id, { enabled: !category.enabled })) }
  catch (cause) { alert.value = errorMessage(cause) }
}

async function saveCategory(category: Category) {
  if (!isJavaInteger(category.sortOrder)) {
    alert.value = '分类排序必须是 Java int 范围内的整数'
    return
  }
  try {
    Object.assign(category, await updateCategory(category.id, {
      name: category.name.trim(), sortOrder: Number(category.sortOrder), enabled: category.enabled,
    }))
    notice.value = '分类已保存'
  } catch (cause) { alert.value = errorMessage(cause) }
}

async function addBrand() {
  if (!newBrandName.value.trim()) return
  try {
    brands.value.push(await createBrand(newBrandName.value.trim()))
    newBrandName.value = ''
  } catch (cause) { alert.value = errorMessage(cause) }
}

async function toggleBrand(brand: Brand) {
  try { Object.assign(brand, await updateBrand(brand.id, { enabled: !brand.enabled })) }
  catch (cause) { alert.value = errorMessage(cause) }
}

async function saveBrand(brand: Brand) {
  try {
    Object.assign(brand, await updateBrand(brand.id, {
      name: brand.name.trim(), remark: brand.remark?.trim() || null, enabled: brand.enabled,
    }))
    notice.value = '品牌已保存'
  } catch (cause) { alert.value = errorMessage(cause) }
}

async function addProduct() {
  if (!newProduct.productName.trim() || !newProduct.categoryId || !newProduct.brandId) {
    alert.value = '商品名称、分类和品牌为必填项'
    return
  }
  try {
    const created = await createProduct({
      ...newProduct,
      productName: newProduct.productName.trim(),
      imageUrl: newProduct.imageUrl.trim() || null,
      description: newProduct.description.trim() || null,
    })
    products.value.unshift(created)
    Object.assign(newProduct, { productName: '', categoryId: '', brandId: '', imageUrl: '', description: '' })
    notice.value = '商品创建成功，可在扫码入库快速建档时新增 SKU。'
  } catch (cause) { alert.value = errorMessage(cause) }
}

async function beginEdit(product: Product, event?: MouseEvent) {
  editTrigger.value = event?.currentTarget as HTMLElement | null
  originalProduct.value = product
  addingSku.value = false
  resetNewSku()
  editingId.value = product.id
  Object.assign(editProduct, {
    productName: product.name,
    categoryId: product.categoryId,
    brandId: product.brandId,
    imageUrl: product.imageUrl ?? '',
    description: product.description ?? '',
    enabled: product.enabled,
    skus: product.skus.map((sku) => ({
      ...sku,
      specsText: formatSpecsForEdit(sku.specs),
      retailPrice: String(sku.retailPrice),
      warningStock: String(sku.warningStock),
    })),
  })
  await nextTick()
  editCloseButton.value?.focus()
}

function resetNewSku() {
  Object.assign(newSku, { skuCode: '', barcode: '', specsText: '', retailPrice: '', warningStock: '0', enabled: true })
}

function startAddSku() {
  alert.value = ''
  resetNewSku()
  addingSku.value = true
}

async function createNewSku() {
  if (creatingSku.value || !editingId.value || !originalProduct.value) return
  alert.value = ''
  if (!newSku.skuCode.trim() || !newSku.barcode.trim()) {
    alert.value = 'SKU 编码和条码为必填项'
    return
  }
  if (!isNonNegativeMoney(newSku.retailPrice)) {
    alert.value = '零售价最多保留 2 位小数，且不得小于 0'
    return
  }
  if (!isNonNegativeInteger(newSku.warningStock)) {
    alert.value = '库存预警值必须是非负整数'
    return
  }
  creatingSku.value = true
  try {
    const product = originalProduct.value
    const created = await quickCreateSku({
      existingSpuId: product.id,
      categoryId: product.categoryId,
      brandId: product.brandId,
      productName: product.name,
      skuCode: newSku.skuCode.trim(),
      barcode: newSku.barcode.trim(),
      specs: parseSpecs(newSku.specsText),
      retailPrice: Number(newSku.retailPrice),
      warningStock: Number(newSku.warningStock),
    })
    editProduct.skus.push({
      ...created,
      specsText: formatSpecsForEdit(created.specs),
      retailPrice: String(created.retailPrice),
      warningStock: String(created.warningStock),
      enabled: newSku.enabled,
    })
    addingSku.value = false
    notice.value = 'SKU 已创建；请保存全部修改以应用启停等编辑。'
  } catch (cause) { alert.value = errorMessage(cause) }
  finally { creatingSku.value = false }
}

async function closeEdit() {
  editingId.value = ''
  await nextTick()
  editTrigger.value?.focus()
}

async function saveProduct() {
  if (saving.value) return
  if (!editingId.value) return
  alert.value = ''
  if (!editProduct.productName.trim() || !editProduct.categoryId || !editProduct.brandId) {
    alert.value = '商品名称、分类和品牌为必填项'
    return
  }
  for (const sku of editProduct.skus) {
    if (!sku.skuCode.trim() || !sku.barcode.trim()) {
      alert.value = 'SKU 编码和条码为必填项'
      return
    }
    if (!isNonNegativeMoney(sku.retailPrice)) {
      alert.value = '零售价最多保留 2 位小数，且不得小于 0'
      return
    }
    if (!isNonNegativeInteger(sku.warningStock)) {
      alert.value = '库存预警值必须是非负整数'
      return
    }
  }
  saving.value = true
  try {
    await updateProduct(editingId.value, {
      productName: editProduct.productName.trim(),
      categoryId: editProduct.categoryId,
      brandId: editProduct.brandId,
      imageUrl: editProduct.imageUrl.trim() || null,
      description: editProduct.description.trim() || null,
      enabled: editProduct.enabled,
      skus: editProduct.skus.map((sku) => ({
        skuId: sku.id,
        skuCode: sku.skuCode.trim(),
        barcode: sku.barcode.trim(),
        specs: parseSpecs(sku.specsText),
        retailPrice: Number(sku.retailPrice),
        warningStock: Number(sku.warningStock),
        enabled: sku.enabled,
      })),
    })
    notice.value = '商品资料已保存'
    editingId.value = ''
    await load()
  } catch (cause) { alert.value = errorMessage(cause) }
  finally { saving.value = false }
}

function categoryName(id: string) { return categories.value.find((item) => item.id === id)?.name ?? '—' }
function brandName(id: string) { return brands.value.find((item) => item.id === id)?.name ?? '—' }
function specsText(specs: Record<string, string>) { return Object.entries(specs).map(([name, value]) => `${name}：${value}`).join(' / ') || '无规格' }
function formatSpecsForEdit(specs: Record<string, string>) { return Object.entries(specs).map(([name, value]) => `${name}:${value}`).join(',') }
function parseSpecs(text: string) {
  if (!text.trim()) return {}
  return Object.fromEntries(text.split(',').map((part) => {
    const separator = part.indexOf(':')
    if (separator <= 0 || !part.slice(separator + 1).trim()) throw new Error('规格格式应为“名称:值”，多项用英文逗号分隔')
    return [part.slice(0, separator).trim(), part.slice(separator + 1).trim()]
  }))
}
</script>

<template>
  <main class="page">
    <header><p class="eyebrow">基础资料</p><h1>商品管理</h1><p>维护商品分类、品牌、SPU 与具体 SKU。</p></header>
    <p v-if="alert" role="alert" class="alert">{{ alert }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>

    <div class="dictionary-grid">
      <section class="card" aria-labelledby="categories-title">
        <h2 id="categories-title">分类管理</h2>
        <form class="inline-form" @submit.prevent="addCategory"><label>分类名称<input v-model="newCategoryName" required></label><button type="submit">新增分类</button></form>
        <ul><li v-for="category in categories" :key="category.id" class="dictionary-row">
          <label>分类名称<input v-model="category.name" :data-testid="`category-name-${category.id}`"></label>
          <label>排序<input v-model.number="category.sortOrder" :data-testid="`category-sort-${category.id}`" type="number" step="1"></label>
          <button :data-testid="`save-category-${category.id}`" type="button" class="secondary" @click="saveCategory(category)">保存</button>
          <button type="button" class="link" @click="toggleCategory(category)">{{ category.enabled ? '停用' : '启用' }}</button>
        </li></ul>
      </section>
      <section class="card" aria-labelledby="brands-title">
        <h2 id="brands-title">品牌管理</h2>
        <form class="inline-form" @submit.prevent="addBrand"><label>品牌名称<input v-model="newBrandName" required></label><button type="submit">新增品牌</button></form>
        <ul><li v-for="brand in brands" :key="brand.id" class="dictionary-row">
          <label>品牌名称<input v-model="brand.name" :data-testid="`brand-name-${brand.id}`"></label>
          <label>品牌备注<input v-model="brand.remark" :data-testid="`brand-remark-${brand.id}`"></label>
          <button :data-testid="`save-brand-${brand.id}`" type="button" class="secondary" @click="saveBrand(brand)">保存</button>
          <button type="button" class="link" @click="toggleBrand(brand)">{{ brand.enabled ? '停用' : '启用' }}</button>
        </li></ul>
      </section>
    </div>

    <section class="card" aria-labelledby="new-product-title">
      <h2 id="new-product-title">新建商品</h2>
      <form class="product-form" @submit.prevent="addProduct">
        <label>商品名称<input v-model="newProduct.productName" required></label>
        <label>商品分类<select v-model="newProduct.categoryId" required><option value="">请选择</option><option v-for="category in categories.filter((item) => item.enabled)" :key="category.id" :value="category.id">{{ category.name }}</option></select></label>
        <label>品牌<select v-model="newProduct.brandId" required><option value="">请选择</option><option v-for="brand in brands.filter((item) => item.enabled)" :key="brand.id" :value="brand.id">{{ brand.name }}</option></select></label>
        <label>图片 URL<input v-model="newProduct.imageUrl" type="url"></label>
        <label class="wide">商品描述<textarea v-model="newProduct.description" rows="2"></textarea></label>
        <button type="submit">创建商品</button>
      </form>
    </section>

    <section class="card" aria-labelledby="products-title">
      <div class="section-heading"><h2 id="products-title">商品与 SKU</h2><span>{{ products.length }} 个商品</span></div>
      <p v-if="loading">正在加载…</p>
      <div v-else class="product-list">
        <article v-for="product in products" :key="product.id" class="product">
          <div class="product-summary">
            <img v-if="product.imageUrl" :src="product.imageUrl" :alt="`${product.name} 商品图`">
            <div class="image-placeholder" v-else>暂无图片</div>
            <div><h3>{{ product.name }}</h3><p>{{ categoryName(product.categoryId) }} · {{ brandName(product.brandId) }}</p><p>{{ product.description || '暂无描述' }}</p><span class="tag">启用状态：{{ product.enabled ? '启用' : '停用' }}</span></div>
            <button :data-testid="`edit-product-${product.id}`" type="button" class="secondary" @click="beginEdit(product, $event)">编辑资料</button>
          </div>
          <table><thead><tr><th>SKU 编码</th><th>条码</th><th>规格</th><th>零售价</th><th>库存预警值</th><th>启用状态</th></tr></thead>
            <tbody><tr v-for="sku in product.skus" :key="sku.id"><td>{{ sku.skuCode }}</td><td>{{ sku.barcode }}</td><td>{{ specsText(sku.specs) }}</td><td>¥{{ Number(sku.retailPrice).toFixed(2) }}</td><td>{{ sku.warningStock }}</td><td>{{ sku.enabled ? '启用' : '停用' }}</td></tr><tr v-if="!product.skus.length"><td colspan="6">尚无 SKU，可在“编辑资料”中新增。</td></tr></tbody>
          </table>
        </article>
      </div>
    </section>

    <div v-if="editingId" class="overlay" role="dialog" aria-modal="true" aria-labelledby="edit-title" @keydown.esc="closeEdit">
      <form class="dialog" @submit.prevent="saveProduct">
        <header class="section-heading"><h2 id="edit-title">编辑商品资料</h2><button ref="editCloseButton" data-testid="edit-product-close" type="button" class="close" aria-label="关闭" @click="closeEdit">×</button></header>
        <div class="product-form">
          <label>商品名称<input v-model="editProduct.productName" required></label>
          <label>商品分类<select v-model="editProduct.categoryId" required><option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option></select></label>
          <label>品牌<select v-model="editProduct.brandId" required><option v-for="brand in brands" :key="brand.id" :value="brand.id">{{ brand.name }}</option></select></label>
          <label>图片 URL<input v-model="editProduct.imageUrl" data-testid="product-image-url" type="url"></label>
          <label class="wide">商品描述<textarea v-model="editProduct.description" data-testid="product-description" rows="2"></textarea></label>
          <label class="check"><input v-model="editProduct.enabled" type="checkbox">启用状态</label>
        </div>
        <div class="section-heading sku-heading"><h3>SKU 规格</h3><button data-testid="add-sku" type="button" class="secondary" @click="startAddSku">新增 SKU</button></div>
        <fieldset v-if="addingSku" class="new-sku">
          <legend>新增 SKU</legend><div class="sku-grid">
            <label>SKU 编码<input v-model="newSku.skuCode" data-testid="new-sku-code" required></label>
            <label>条码<input v-model="newSku.barcode" data-testid="new-sku-barcode" required></label>
            <label>规格（含颜色/尺码）<input v-model="newSku.specsText" data-testid="new-sku-specs" placeholder="颜色:蓝色,尺码:42"></label>
            <label>零售价<input v-model="newSku.retailPrice" data-testid="new-sku-retail-price" type="number" min="0" step="0.01" required></label>
            <label>库存预警值<input v-model="newSku.warningStock" data-testid="new-sku-warning-stock" type="number" min="0" step="1" required></label>
            <label class="check"><input v-model="newSku.enabled" data-testid="new-sku-enabled" type="checkbox">启用状态</label>
          </div>
          <div class="sku-actions"><button type="button" class="secondary" @click="addingSku = false">取消新增</button><button data-testid="create-sku" type="button" :disabled="creatingSku" @click="createNewSku">{{ creatingSku ? '创建中…' : '创建 SKU' }}</button></div>
        </fieldset>
        <fieldset v-for="sku in editProduct.skus" :key="sku.id">
          <legend>{{ sku.skuCode }}</legend><div class="sku-grid">
            <label>SKU 编码<input v-model="sku.skuCode" required></label><label>条码<input v-model="sku.barcode" required></label>
            <label>规格<input v-model="sku.specsText" :data-testid="`sku-specs-${sku.id}`"></label>
            <label>零售价<input v-model="sku.retailPrice" :data-testid="`sku-retail-price-${sku.id}`" type="number" min="0" step="0.01" required></label>
            <label>库存预警值<input v-model="sku.warningStock" :data-testid="`sku-warning-stock-${sku.id}`" type="number" min="0" step="1" required></label>
            <label class="check"><input v-model="sku.enabled" type="checkbox">启用状态</label>
          </div>
        </fieldset>
        <footer><button type="button" class="secondary" @click="closeEdit">取消</button><button data-testid="save-product" type="button" :disabled="saving" @click="saveProduct">{{ saving ? '保存中…' : '保存全部修改' }}</button></footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.page { max-width: 82rem; margin: 0 auto; display: grid; gap: 1rem; color: #0f172a; } h1, h2, h3, p { margin-top: 0; }.eyebrow { color: #2563eb; font-weight: 800; font-size: .78rem; margin-bottom: .3rem; }
.card { background: white; border: 1px solid #e2e8f0; border-radius: .75rem; padding: 1.2rem; }.dictionary-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
.inline-form { display: flex; align-items: end; gap: .7rem; }.inline-form label { flex: 1; } ul { list-style: none; padding: 0; }.dictionary-row { display: grid; grid-template-columns: minmax(8rem, 1fr) minmax(6rem, 1fr) auto auto; align-items: end; gap: .5rem; border-top: 1px solid #e2e8f0; padding: .65rem 0; }
label { display: grid; gap: .3rem; color: #334155; font-size: .88rem; } input, select, textarea { font: inherit; border: 1px solid #cbd5e1; border-radius: .4rem; padding: .6rem; } button { font: inherit; border: 0; border-radius: .4rem; padding: .65rem .9rem; background: #2563eb; color: white; }.secondary { background: #e2e8f0; color: #1e293b; }.link, .close { background: none; color: #2563eb; padding: .2rem; }
.product-form { display: grid; grid-template-columns: repeat(4, 1fr); align-items: end; gap: .8rem; }.wide { grid-column: span 3; }.section-heading { display: flex; align-items: center; justify-content: space-between; }
.product { border: 1px solid #e2e8f0; border-radius: .6rem; overflow: hidden; margin-top: 1rem; }.product-summary { display: grid; grid-template-columns: 5rem 1fr auto; gap: 1rem; padding: 1rem; align-items: center; }.product-summary img, .image-placeholder { width: 5rem; height: 5rem; border-radius: .5rem; object-fit: cover; background: #f1f5f9; display: grid; place-items: center; color: #94a3b8; font-size: .75rem; }.tag { color: #475569; font-size: .82rem; }
table { width: 100%; border-collapse: collapse; } th, td { padding: .65rem 1rem; text-align: left; border-top: 1px solid #e2e8f0; } th { color: #475569; font-size: .82rem; }.alert { color: #b91c1c; }.notice { color: #166534; }
.overlay { position: fixed; inset: 0; z-index: 20; background: rgb(15 23 42 / .5); display: grid; place-items: center; padding: 1rem; }.dialog { width: min(70rem, 100%); max-height: 90vh; overflow: auto; border-radius: .8rem; padding: 1.2rem; background: white; }.close { font-size: 1.5rem; color: #334155; } fieldset { border: 1px solid #e2e8f0; margin: 1rem 0; border-radius: .5rem; }.sku-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: .75rem; }.check { display: flex; flex-direction: row; align-items: center; }.dialog footer, .sku-actions { display: flex; justify-content: flex-end; gap: .75rem; }.sku-heading { margin-top: 1.2rem; }.sku-heading h3 { margin-bottom: 0; }.new-sku { background: #f8fafc; }
@media (max-width: 800px) { .dictionary-grid, .product-form, .sku-grid { grid-template-columns: 1fr; }.dictionary-row { grid-template-columns: 1fr 1fr; }.wide { grid-column: auto; }.product-summary { grid-template-columns: 1fr; } }
</style>
