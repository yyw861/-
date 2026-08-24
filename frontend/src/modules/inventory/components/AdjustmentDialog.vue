<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { errorMessage } from '../../catalog/api'
import { createAdjustment } from '../api'
import type { AdjustmentReceipt, InventoryItem } from '../types'

const props = defineProps<{ open: boolean; item: InventoryItem }>()
const emit = defineEmits<{ close: []; success: [receipt: AdjustmentReceipt] }>()

const countedQuantity = ref<number>(props.item.quantity)
const reason = ref('')
const submitting = ref(false)
const alert = ref('')
const requestId = ref(crypto.randomUUID())
const submittedFingerprint = ref('')
const difference = computed(() => Number.isFinite(countedQuantity.value)
  ? countedQuantity.value - props.item.quantity : 0)
const valid = computed(() => Number.isInteger(countedQuantity.value) && countedQuantity.value >= 0
  && difference.value !== 0 && reason.value.trim().length > 0)

watch(() => [props.open, props.item.skuId] as const, ([open]) => {
  if (open) {
    countedQuantity.value = props.item.quantity
    reason.value = ''
    alert.value = ''
    requestId.value = crypto.randomUUID()
    submittedFingerprint.value = ''
  }
})

function signed(value: number) {
  return value > 0 ? `+${value}` : String(value)
}

async function submit() {
  if (!valid.value || submitting.value) return
  submitting.value = true
  alert.value = ''
  try {
    const command = { lines: [{
      skuId: props.item.skuId,
      systemQuantity: props.item.quantity,
      countedQuantity: countedQuantity.value,
      reason: reason.value.trim(),
    }] }
    const fingerprint = JSON.stringify(command)
    if (submittedFingerprint.value && submittedFingerprint.value !== fingerprint) {
      requestId.value = crypto.randomUUID()
    }
    submittedFingerprint.value = fingerprint
    const receipt = await createAdjustment(command, requestId.value)
    emit('success', receipt)
  } catch (cause) {
    alert.value = errorMessage(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div v-if="open" class="overlay" role="dialog" aria-modal="true" aria-labelledby="adjustment-title">
    <section class="dialog-card">
      <header><div><h2 id="adjustment-title">库存盘点调整</h2><p>{{ item.productName }} · {{ item.skuCode }}</p></div><button type="button" class="close" aria-label="关闭" @click="emit('close')">×</button></header>
      <p class="snapshot">系统库存 {{ item.quantity }}，平均成本 {{ Number(item.averageCost).toFixed(4) }}</p>
      <label>实际盘点数量<input v-model.number="countedQuantity" data-testid="counted-quantity" type="number" min="0" step="1"></label>
      <p data-testid="adjustment-difference" :class="['difference', difference > 0 ? 'gain' : 'loss']">库存差异 {{ signed(difference) }}</p>
      <label>调整原因<textarea v-model="reason" data-testid="adjustment-reason" maxlength="200" placeholder="例如：盘盈复核、破损报废"></textarea></label>
      <p v-if="alert" role="alert" class="error">{{ alert }}</p>
      <footer><button type="button" class="secondary" @click="emit('close')">取消</button><button type="button" data-testid="confirm-adjustment" :disabled="!valid || submitting" @click="submit">{{ submitting ? '提交中…' : '确认调整' }}</button></footer>
    </section>
  </div>
</template>

<style scoped>
.overlay{position:fixed;inset:0;z-index:30;background:#0f172a88;display:grid;place-items:center;padding:1rem}.dialog-card{width:min(32rem,100%);background:white;border-radius:.8rem;padding:1.3rem;color:#0f172a}.dialog-card header{display:flex;justify-content:space-between;gap:1rem}.dialog-card h2,.dialog-card p{margin-top:0}.dialog-card header p{color:#64748b}.close{background:none;color:#334155;font-size:1.5rem;padding:.1rem}.snapshot{padding:.75rem;background:#f1f5f9;border-radius:.5rem}label{display:grid;gap:.35rem;margin-top:1rem;color:#334155}input,textarea{font:inherit;border:1px solid #cbd5e1;border-radius:.4rem;padding:.65rem}textarea{min-height:5rem;resize:vertical}.difference{font-size:1.15rem;font-weight:800;margin:.75rem 0}.gain{color:#047857}.loss{color:#b91c1c}.error{color:#b91c1c}footer{display:flex;justify-content:flex-end;gap:.6rem;margin-top:1rem}button{font:inherit;border:0;border-radius:.4rem;padding:.65rem .9rem;color:white;background:#2563eb;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}.secondary{background:#e2e8f0;color:#334155}
</style>
