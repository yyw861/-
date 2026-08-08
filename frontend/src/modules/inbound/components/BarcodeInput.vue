<script setup lang="ts">
import { nextTick, ref } from 'vue'

defineProps<{ disabled: boolean; busy?: boolean }>()
const emit = defineEmits<{ submit: [barcode: string] }>()

const barcode = ref('')
const input = ref<HTMLInputElement>()

function submit() {
  const value = barcode.value.trim()
  if (!value) return
  barcode.value = ''
  emit('submit', value)
  void focus()
}

async function focus() {
  await nextTick()
  input.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <form data-testid="scan-form" class="scanner" aria-label="扫描商品条码" @submit.prevent="submit">
    <label for="inbound-barcode">商品条码</label>
    <input
      id="inbound-barcode"
      ref="input"
      v-model="barcode"
      data-testid="barcode-input"
      inputmode="numeric"
      autocomplete="off"
      :disabled="disabled || busy"
      placeholder="请扫描或输入条码"
    >
    <button data-testid="scan-submit" type="submit" :disabled="disabled || busy || !barcode.trim()">
      {{ busy ? '查询中…' : '确认条码' }}
    </button>
  </form>
</template>

<style scoped>
.scanner { display: grid; grid-template-columns: auto minmax(16rem, 1fr) auto; align-items: center; gap: .75rem; }
input { font: inherit; padding: .7rem; border: 1px solid #cbd5e1; border-radius: .45rem; }
button { padding: .7rem 1rem; }
@media (max-width: 650px) { .scanner { grid-template-columns: 1fr; } }
</style>
