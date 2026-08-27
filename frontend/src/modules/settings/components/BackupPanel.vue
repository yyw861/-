<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorMessage } from '@/modules/catalog/api'
import { formatBusinessDateTime } from '@/shared/format/dateTime'
import { createBackup, getBackups, previewRestore, restoreBackup } from '../api'
import type { BackupItem, RestorePreview } from '../types'

const emit = defineEmits<{ restored: [] }>()

const backups = ref<BackupItem[]>([])
const preview = ref<RestorePreview | null>(null)
const confirmation = ref('')
const busy = ref(false)
const alert = ref('')
const notice = ref('')

onMounted(load)
async function load() { try { backups.value = await getBackups() } catch (cause) { alert.value = errorMessage(cause) } }
async function create() { await act(async () => { const value = await createBackup(); backups.value = [value, ...backups.value]; notice.value = '备份创建成功' }) }
async function showPreview(item: BackupItem) { await act(async () => { preview.value = await previewRestore(item.id); confirmation.value = '' }) }
async function restore() {
  if (!preview.value) return
  await act(async () => { await restoreBackup(preview.value!.backupId, confirmation.value); notice.value = '数据恢复成功，系统已自动创建恢复前保护备份'; preview.value = null; confirmation.value = ''; await load(); emit('restored') })
}
async function act(action: () => Promise<void>) { alert.value = ''; notice.value = ''; busy.value = true; try { await action() } catch (cause) { alert.value = errorMessage(cause) } finally { busy.value = false } }
function size(value: number) { return value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB` }
</script>

<template>
  <section class="card backup-panel">
    <div class="section-heading"><div><h2>数据备份与恢复</h2><p>备份包含全部商品、库存、销售和设置数据。</p></div><button data-testid="create-backup" :disabled="busy" @click="create">立即备份</button></div>
    <p v-if="alert" role="alert" class="alert">{{ alert }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div class="table-wrap"><table><thead><tr><th>创建时间</th><th>文件</th><th>类型</th><th>大小</th><th>状态</th><th></th></tr></thead><tbody>
      <tr v-for="item in backups" :key="item.id"><td>{{ formatBusinessDateTime(item.createdAt) }}</td><td>{{ item.fileName }}</td><td>{{ item.backupType === 'PRE_RESTORE' ? '恢复前保护' : '手动备份' }}</td><td>{{ size(item.fileSize) }}</td><td>{{ item.status === 'SUCCEEDED' ? '可用' : item.status === 'FAILED' ? '失败' : '处理中' }}</td><td><button class="secondary" :data-testid="`preview-${item.id}`" :disabled="busy || item.status !== 'SUCCEEDED'" @click="showPreview(item)">恢复</button></td></tr>
      <tr v-if="!backups.length"><td colspan="6" class="empty">暂无备份</td></tr>
    </tbody></table></div>
    <div v-if="preview" class="restore-box"><h3>确认恢复 {{ preview.fileName }}</h3><p>{{ preview.message }} · 迁移版本 {{ preview.schemaVersion }} · {{ size(preview.fileSize) }}</p><p class="warning">恢复会替换当前数据。系统将先自动生成保护备份。请输入“恢复数据”继续。</p><div class="restore-actions"><input data-testid="restore-text" v-model="confirmation" placeholder="恢复数据"><button data-testid="confirm-restore" :disabled="busy || confirmation !== '恢复数据'" @click="restore">确认恢复</button><button class="secondary" @click="preview = null">取消</button></div></div>
  </section>
</template>

<style scoped>
.card{background:#fff;border:1px solid #e2e8f0;border-radius:.75rem;padding:1rem}.section-heading{display:flex;align-items:end;justify-content:space-between;gap:1rem}.section-heading p,h2,h3{margin-top:0}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;margin-top:.8rem}th,td{padding:.7rem;text-align:left;border-top:1px solid #e2e8f0;white-space:nowrap}button{border:0;border-radius:.4rem;padding:.62rem .9rem;background:#2563eb;color:#fff;font:inherit;cursor:pointer}button:disabled{opacity:.45}.secondary{background:#e2e8f0;color:#334155}.restore-box{margin-top:1rem;padding:1rem;border:1px solid #f59e0b;border-radius:.6rem;background:#fffbeb}.warning,.alert{color:#b91c1c}.notice{color:#047857}.restore-actions{display:flex;gap:.6rem}.restore-actions input{border:1px solid #cbd5e1;border-radius:.4rem;padding:.6rem}.empty{text-align:center;color:#94a3b8}@media(max-width:720px){.section-heading,.restore-actions{align-items:stretch;flex-direction:column}}
</style>
