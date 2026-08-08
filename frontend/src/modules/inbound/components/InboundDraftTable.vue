<script setup lang="ts">
import type { DraftLine } from '../types'

defineProps<{ lines: DraftLine[] }>()
defineEmits<{ remove: [id: string] }>()

function specsText(specs: Record<string, string>) {
  return Object.entries(specs).map(([name, value]) => `${name}：${value}`).join(' / ') || '无规格'
}
</script>

<template>
  <div data-testid="draft-table" class="table-wrap">
    <table>
      <thead><tr><th>商品</th><th>SKU / 规格</th><th>数量</th><th>进价</th><th>小计</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-if="lines.length === 0"><td colspan="6" class="empty">尚未添加商品</td></tr>
        <tr v-for="line in lines" :key="line.id">
          <td>{{ line.productName }}</td>
          <td>{{ line.sku.skuCode }}<small>{{ specsText(line.sku.specs) }}</small></td>
          <td>{{ line.quantity }}</td>
          <td>¥{{ line.unitCost.toFixed(2) }}</td>
          <td>¥{{ (line.quantity * line.unitCost).toFixed(2) }}</td>
          <td><button type="button" class="link danger" @click="$emit('remove', line.id)">移除</button></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: .75rem; text-align: left; border-bottom: 1px solid #e2e8f0; }
th { color: #475569; font-size: .86rem; }
small { display: block; color: #64748b; margin-top: .2rem; }
.empty { text-align: center; color: #94a3b8; }
.link { border: 0; background: none; padding: .25rem; }
.danger { color: #b91c1c; }
</style>
