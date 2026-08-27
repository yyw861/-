<script setup lang="ts">
import { computed, ref, watch } from 'vue'
const props = defineProps<{ open: boolean; actualAmount: number; submitting: boolean }>()
const emit = defineEmits<{ close: []; confirm: [method: string, amount: number] }>()
const method = ref('CASH'); const amount = ref('')
watch(() => props.open, (open) => { if (open) amount.value = props.actualAmount.toFixed(2) })
const valid = computed(() => Math.round(Number(amount.value) * 100) === Math.round(props.actualAmount * 100))
</script>
<template><div v-if="open" class="overlay" role="dialog" aria-modal="true"><section><h2>确认收款</h2><p class="due">应收 ¥{{ actualAmount.toFixed(2) }}</p>
  <label>支付方式<select v-model="method"><option value="CASH">现金</option><option value="WECHAT">微信</option><option value="ALIPAY">支付宝</option><option value="BANK_CARD">银行卡</option></select></label>
  <label>支付金额<input v-model="amount" data-testid="payment-amount" type="number" min="0" step="0.01"></label>
  <p v-if="!valid" class="error">支付金额必须等于应收金额</p><footer><button type="button" @click="emit('close')">取消</button><button data-testid="confirm-payment" type="button" :disabled="!valid || submitting" @click="emit('confirm', method, Number(amount))">{{ submitting ? '提交中…' : '确认收款' }}</button></footer>
</section></div></template>
<style scoped>.overlay{position:fixed;inset:0;background:#0f172a88;display:grid;place-items:center;z-index:20}.overlay section{background:#fff;border-radius:.8rem;padding:1.5rem;width:min(24rem,90vw);display:grid;gap:1rem}.due{font-size:1.6rem;font-weight:800}label{display:grid;gap:.35rem}input,select{padding:.65rem}.error{color:#b91c1c}footer{display:flex;justify-content:flex-end;gap:.7rem}</style>
