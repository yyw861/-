<script setup lang="ts">
defineProps<{ mode: 'day' | 'month' | 'custom'; fromDate: string; toDate: string }>()
const emit = defineEmits<{ mode: [value: 'day' | 'month' | 'custom']; apply: []; 'update:fromDate': [value: string]; 'update:toDate': [value: string] }>()
</script>

<template>
  <div class="range-filter">
    <div class="presets"><button data-testid="range-day" type="button" :aria-pressed="mode==='day'" :class="{active:mode==='day'}" @click="emit('mode','day')">本日</button><button data-testid="range-month" type="button" :aria-pressed="mode==='month'" :class="{active:mode==='month'}" @click="emit('mode','month')">本月</button><button data-testid="range-custom" type="button" :aria-pressed="mode==='custom'" :class="{active:mode==='custom'}" @click="emit('mode','custom')">自定义</button></div>
    <div v-if="mode==='custom'" class="custom"><label>开始日期<input data-testid="from-date" type="date" :value="fromDate" @input="emit('update:fromDate', ($event.target as HTMLInputElement).value)"></label><label>结束日期<input data-testid="to-date" type="date" :value="toDate" @input="emit('update:toDate', ($event.target as HTMLInputElement).value)"></label><button data-testid="apply-range" type="button" @click="emit('apply')">应用</button></div>
  </div>
</template>

<style scoped>.range-filter,.presets,.custom{display:flex;align-items:end;gap:.65rem;flex-wrap:wrap}.presets button{background:#e2e8f0;color:#334155}.presets .active{background:#2563eb;color:white}label{display:grid;gap:.25rem;font-size:.85rem;color:#475569}input{font:inherit;border:1px solid #cbd5e1;border-radius:.4rem;padding:.55rem}button{font:inherit;border:0;border-radius:.4rem;padding:.6rem .85rem;background:#2563eb;color:white;cursor:pointer}</style>
