<script setup lang="ts">
import type { CartLine } from '../types'
defineProps<{ lines: CartLine[] }>()
const emit = defineEmits<{ quantity: [skuId: string, quantity: number]; remove: [skuId: string] }>()
</script>
<template><div class="cart" data-testid="checkout-cart">
  <table v-if="lines.length"><thead><tr><th>商品</th><th>单价</th><th>数量</th><th>小计</th><th></th></tr></thead>
    <tbody><tr v-for="line in lines" :key="line.skuId"><td><strong>{{ line.productName }}</strong><small>{{ line.skuCode }} · {{ line.barcode }} · 库存 {{ line.available }}</small></td>
      <td>¥{{ line.unitPrice.toFixed(2) }}</td><td><input :data-testid="`quantity-${line.skuId}`" type="number" min="1" :max="line.available" :value="line.quantity" @change="emit('quantity', line.skuId, Number(($event.target as HTMLInputElement).value))"></td>
      <td>¥{{ (line.unitPrice * line.quantity).toFixed(2) }}</td><td><button type="button" @click="emit('remove', line.skuId)">移除</button></td></tr></tbody></table>
  <p v-else class="empty">扫描商品条码后将自动加入购物车</p>
</div></template>
<style scoped>table{width:100%;border-collapse:collapse}th,td{padding:.8rem;border-bottom:1px solid #e2e8f0;text-align:left}small{display:block;color:#64748b;margin-top:.2rem}input{width:5rem;padding:.4rem}.empty{text-align:center;color:#64748b;padding:2rem}</style>
