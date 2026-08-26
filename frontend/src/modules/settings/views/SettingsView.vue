<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { errorMessage } from '@/modules/catalog/api'
import { formatBusinessDateTime } from '@/shared/format/dateTime'
import { createPaymentMethod, getDocumentNumbering, getOperationLogs, getPaymentMethods,
  getReceiptSetting, getStoreSetting, patchPaymentMethod, updateDocumentNumbering,
  updateReceiptSetting, updateStoreSetting } from '../api'
import type { DocumentNumbering, OperationLogItem, PaymentMethod } from '../types'
import BackupPanel from '../components/BackupPanel.vue'

const storeForm = reactive({ storeName: '', phone: '', address: '', deviceName: '' })
const receiptForm = reactive({ headerText: '', footerText: '', showPhone: true, showAddress: true, paperWidth: 58 as 58 | 80 })
const paymentForm = reactive({ code: '', name: '', sortOrder: 50 })
const numberings = ref<DocumentNumbering[]>([])
const payments = ref<PaymentMethod[]>([])
const logs = ref<OperationLogItem[]>([])
const loading = ref(true)
const alert = ref('')
const notice = ref('')

onMounted(load)

async function load() {
  loading.value = true
  alert.value = ''
  try {
    const [store, receipt, numbers, methods, logPage] = await Promise.all([
      getStoreSetting(), getReceiptSetting(), getDocumentNumbering(), getPaymentMethods(), getOperationLogs(),
    ])
    Object.assign(storeForm, { ...store, phone: store.phone ?? '', address: store.address ?? '' })
    Object.assign(receiptForm, { ...receipt, headerText: receipt.headerText ?? '', footerText: receipt.footerText ?? '' })
    numberings.value = numbers.map(item => ({ ...item }))
    payments.value = methods
    logs.value = logPage.items
  } catch (cause) { alert.value = errorMessage(cause) }
  finally { loading.value = false }
}

async function saveStore() {
  await act(async () => { await updateStoreSetting({ ...storeForm }); notice.value = '门店资料已保存' })
}
async function saveReceipt() {
  await act(async () => { await updateReceiptSetting({ ...receiptForm }); notice.value = '小票设置已保存' })
}
async function saveNumbering(item: DocumentNumbering) {
  await act(async () => {
    const updated = await updateDocumentNumbering(item.documentType, { prefix: item.prefix, nextValue: item.nextValue })
    Object.assign(item, updated); notice.value = '单号规则已保存'
  })
}
async function addPayment() {
  await act(async () => {
    const created = await createPaymentMethod({ ...paymentForm, code: paymentForm.code.toUpperCase() })
    payments.value = [...payments.value, created].sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code))
    Object.assign(paymentForm, { code: '', name: '', sortOrder: 50 }); notice.value = '支付方式已添加'
  })
}
async function togglePayment(item: PaymentMethod) {
  await act(async () => {
    const updated = await patchPaymentMethod(item.code, { enabled: !item.enabled })
    payments.value = payments.value.map(value => value.code === item.code ? updated : value)
    notice.value = updated.enabled ? '支付方式已启用' : '支付方式已停用'
  })
}
async function act(action: () => Promise<void>) {
  alert.value = ''; notice.value = ''
  try { await action() } catch (cause) { alert.value = errorMessage(cause) }
}

function documentLabel(type: string) {
  return ({ INBOUND: '入库', SALE: '销售', RETURN: '退货', ADJUSTMENT: '盘点调整' } as Record<string, string>)[type] ?? type
}
function operationLabel(type: string) {
  return ({ INBOUND: '入库', SALE: '销售', RETURN: '退货', ADJUSTMENT: '盘点调整', BACKUP: '备份', RESTORE_PREVIEW: '恢复预检', RESTORE: '数据恢复' } as Record<string, string>)[type] ?? type
}
</script>

<template>
  <main class="page">
    <header><p class="eyebrow">单店配置</p><h1>系统设置</h1><p>维护门店、小票、单号与收款方式，并查看关键业务操作记录。</p></header>
    <p v-if="alert" role="alert" class="alert">{{ alert }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <p v-if="loading" class="muted">正在读取设置…</p>
    <template v-else>
      <section class="card"><div class="section-heading"><div><h2>门店资料</h2><p>用于小票展示和设备操作标识。</p></div><button data-testid="save-store" @click="saveStore">保存门店资料</button></div><div class="form-grid"><label>门店名称<input data-testid="store-name" v-model="storeForm.storeName"></label><label>联系电话<input v-model="storeForm.phone"></label><label>门店地址<input v-model="storeForm.address"></label><label>设备名称<input data-testid="device-name" v-model="storeForm.deviceName"></label></div></section>
      <section class="card"><div class="section-heading"><div><h2>小票设置</h2><p>设置打印内容与纸张宽度。</p></div><button data-testid="save-receipt" @click="saveReceipt">保存小票设置</button></div><div class="form-grid"><label>抬头<input v-model="receiptForm.headerText"></label><label>页脚<input v-model="receiptForm.footerText"></label><label>纸宽<select data-testid="paper-width" v-model.number="receiptForm.paperWidth"><option :value="58">58 mm</option><option :value="80">80 mm</option></select></label><label class="checks"><span><input v-model="receiptForm.showPhone" type="checkbox"> 显示电话</span><span><input v-model="receiptForm.showAddress" type="checkbox"> 显示地址</span></label></div></section>
      <section class="card"><h2>单号规则</h2><div class="table-wrap"><table><thead><tr><th>单据</th><th>前缀</th><th>下一个序号</th><th></th></tr></thead><tbody><tr v-for="item in numberings" :key="item.documentType"><td>{{ documentLabel(item.documentType) }}</td><td><input :data-testid="`number-prefix-${item.documentType}`" v-model="item.prefix"></td><td><input v-model.number="item.nextValue" type="number" min="1"></td><td><button :data-testid="`save-number-${item.documentType}`" @click="saveNumbering(item)">保存</button></td></tr></tbody></table></div></section>
      <section class="card"><h2>支付方式</h2><div class="payment-add"><input data-testid="payment-code" v-model="paymentForm.code" placeholder="代码，如 UNIONPAY"><input data-testid="payment-name" v-model="paymentForm.name" placeholder="名称"><input v-model.number="paymentForm.sortOrder" type="number" aria-label="排序"><button data-testid="add-payment" :disabled="!paymentForm.code.trim() || !paymentForm.name.trim()" @click="addPayment">添加</button></div><div class="table-wrap"><table><thead><tr><th>代码</th><th>名称</th><th>排序</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in payments" :key="item.code"><td>{{ item.code }}</td><td>{{ item.name }}</td><td>{{ item.sortOrder }}</td><td><span :class="item.enabled ? 'success' : 'muted'">{{ item.enabled ? '启用' : '停用' }}</span></td><td><button class="secondary" :data-testid="`toggle-payment-${item.code}`" @click="togglePayment(item)">{{ item.enabled ? '停用' : '启用' }}</button></td></tr></tbody></table></div></section>
      <BackupPanel @restored="load" />
      <section class="card"><div class="section-heading"><div><h2>操作日志</h2><p>仅供查看，记录关键业务操作的结果与设备。</p></div></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作</th><th>对象</th><th>结果</th><th>设备</th><th>说明</th></tr></thead><tbody><tr v-for="item in logs" :key="item.id"><td>{{ formatBusinessDateTime(item.occurredAt) }}</td><td>{{ operationLabel(item.operationType) }}</td><td>{{ item.objectId || item.objectType }}</td><td><span :class="item.result === 'SUCCESS' ? 'success' : 'failure'">{{ item.result === 'SUCCESS' ? '成功' : '失败' }}</span></td><td>{{ item.deviceSummary || '—' }}</td><td>{{ item.message || '—' }}</td></tr><tr v-if="!logs.length"><td colspan="6" class="empty">暂无操作日志</td></tr></tbody></table></div></section>
    </template>
  </main>
</template>

<style scoped>
.page{max-width:82rem;margin:0 auto;display:grid;gap:1rem;color:#0f172a}header h1,header p,h2{margin-top:0}.eyebrow{color:#2563eb;font-size:.78rem;font-weight:800;margin-bottom:.3rem}.card{background:#fff;border:1px solid #e2e8f0;border-radius:.75rem;padding:1rem}.section-heading{display:flex;align-items:end;justify-content:space-between;gap:1rem}.section-heading p{margin:0;color:#64748b}.form-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:.9rem;margin-top:1rem}label{display:grid;gap:.35rem;color:#475569;font-size:.88rem}input,select{min-width:0;border:1px solid #cbd5e1;border-radius:.4rem;padding:.6rem;font:inherit;background:#fff}.checks{display:flex;align-items:center;gap:1rem}.checks span{display:flex;align-items:center;gap:.35rem}button{border:0;border-radius:.4rem;padding:.62rem .9rem;background:#2563eb;color:#fff;font:inherit;cursor:pointer}button:disabled{opacity:.45}.secondary{background:#e2e8f0;color:#334155}.payment-add{display:grid;grid-template-columns:1fr 1fr 7rem auto;gap:.6rem}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;margin-top:.8rem}th,td{padding:.7rem;text-align:left;border-top:1px solid #e2e8f0;white-space:nowrap}th{font-size:.82rem;color:#475569}.success{color:#047857}.failure,.alert{color:#b91c1c}.notice{color:#047857}.muted{color:#64748b}.empty{text-align:center;color:#94a3b8;padding:2rem}@media(max-width:720px){.form-grid{grid-template-columns:1fr}.payment-add{grid-template-columns:1fr}.section-heading{align-items:stretch;flex-direction:column}}
</style>
