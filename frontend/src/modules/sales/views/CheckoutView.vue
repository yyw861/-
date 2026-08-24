<script setup lang="ts">
import { nextTick, ref } from 'vue'; import { storeToRefs } from 'pinia'
import { findSkuByBarcode, getProduct, isNotFound, errorMessage } from '../../catalog/api'
import { getInventory } from '../../inventory/api'; import BarcodeInput from '../../inbound/components/BarcodeInput.vue'
import CheckoutCart from '../components/CheckoutCart.vue'; import PaymentDialog from '../components/PaymentDialog.vue'; import ReceiptDialog from '../components/ReceiptDialog.vue'; import { useCartStore } from '../stores/cart'
const store=useCartStore(); const {lines,discount,remark,submitting,lastReceipt}=storeToRefs(store)
const scanner=ref<InstanceType<typeof BarcodeInput>>(); const scanning=ref(false); const alert=ref(''); const paymentOpen=ref(false); const receiptOpen=ref(false)
async function scan(barcode:string){ if(scanning.value)return; scanning.value=true; alert.value=''; try{const sku=await findSkuByBarcode(barcode); const product=await getProduct(sku.spuId); if(!sku.enabled||!product.enabled)throw new Error('该商品或 SKU 已停用，不能销售'); const page=await getInventory({barcode,page:0,size:10}); const item=page.items.find(i=>i.skuId===sku.id); if(!item)throw new Error('未找到该商品的库存记录'); store.scan({skuId:sku.id,productName:product.name,skuCode:sku.skuCode,barcode:sku.barcode,unitPrice:sku.retailPrice,available:item.quantity})}catch(cause){alert.value=isNotFound(cause)?'未找到该条码商品':errorMessage(cause)}finally{scanning.value=false;await nextTick();await scanner.value?.focus()}}
function quantity(id:string,value:number){try{store.changeQuantity(id,value)}catch(cause){alert.value=errorMessage(cause)}}
function updateDiscount(event:Event){try{store.setDiscount(Number((event.target as HTMLInputElement).value))}catch(cause){alert.value=errorMessage(cause)}}
async function confirm(method:string,amount:number){if(submitting.value)return;try{await store.checkout(method,amount);paymentOpen.value=false;receiptOpen.value=true}catch(cause){alert.value=errorMessage(cause)}}
</script>
<template><main class="page"><header><div><p class="eyebrow">出货管理</p><h1>零售收银</h1><p>扫描条码即可加入购物车。</p></div><RouterLink to="/sales/history">销售记录</RouterLink></header>
  <section class="card"><h2>扫描商品</h2><BarcodeInput ref="scanner" :disabled="false" :busy="scanning" @submit="scan"/><p v-if="alert" role="alert" class="alert">{{ alert }}</p></section>
  <section class="card"><h2>购物车</h2><CheckoutCart :lines="lines" @quantity="quantity" @remove="store.remove"/>
    <div class="summary"><label>优惠金额<input data-testid="discount" type="number" min="0" step="0.01" :value="discount" @change="updateDiscount"></label><label>备注<textarea v-model="remark" maxlength="500" @input="store.changed()"></textarea></label>
      <dl><dt>商品原价</dt><dd>¥{{ store.originalAmount.toFixed(2) }}</dd><dt>优惠</dt><dd>-¥{{ discount.toFixed(2) }}</dd><dt>实收</dt><dd class="actual">¥{{ store.actualAmount.toFixed(2) }}</dd></dl>
      <button data-testid="open-payment" type="button" :disabled="!lines.length || submitting" @click="paymentOpen=true">去收款</button></div></section>
  <PaymentDialog :open="paymentOpen" :actual-amount="store.actualAmount" :submitting="submitting" @close="paymentOpen=false" @confirm="confirm"/>
  <ReceiptDialog :receipt="receiptOpen?lastReceipt:null" @close="receiptOpen=false"/>
</main></template>
<style scoped>.page{display:grid;gap:1rem;max-width:78rem;margin:auto;color:#0f172a}header{display:flex;justify-content:space-between;align-items:center}.eyebrow{color:#2563eb;font-weight:800}.card{background:#fff;border:1px solid #e2e8f0;border-radius:.8rem;padding:1.2rem}.alert{color:#b91c1c;background:#fef2f2;padding:.7rem}.summary{margin-top:1rem;display:grid;grid-template-columns:1fr 1fr auto auto;gap:1rem;align-items:end}.summary label{display:grid;gap:.3rem}.summary input,.summary textarea{padding:.6rem}.summary dl{display:grid;grid-template-columns:auto auto;gap:.25rem 1rem;margin:0}.summary dd{margin:0;text-align:right}.actual{font-size:1.3rem;font-weight:800}.summary button{padding:.8rem 1.4rem;background:#2563eb;color:white;border:0;border-radius:.5rem}@media(max-width:800px){.summary{grid-template-columns:1fr}}</style>
