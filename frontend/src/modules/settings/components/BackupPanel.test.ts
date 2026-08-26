import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BackupPanel from './BackupPanel.vue'

const api = vi.hoisted(() => ({
  getBackups: vi.fn(), createBackup: vi.fn(), previewRestore: vi.fn(), restoreBackup: vi.fn(),
}))
vi.mock('../api', () => api)

const backup = { id: '11111111-1111-1111-1111-111111111111', fileName: 'sportshop-20260824-120000.db',
  filePath: 'data/backups/sportshop.db', sha256: 'a'.repeat(64), fileSize: 4096, backupType: 'MANUAL',
  status: 'SUCCEEDED', createdAt: '2026-08-24T12:00:00', completedAt: '2026-08-24T12:00:01', errorMessage: null }

describe('BackupPanel', () => {
  beforeEach(() => {
    api.getBackups.mockReset().mockResolvedValue([backup])
    api.createBackup.mockReset().mockResolvedValue(backup)
    api.previewRestore.mockReset().mockResolvedValue({ backupId: backup.id, fileName: backup.fileName,
      fileSize: backup.fileSize, sha256: backup.sha256, schemaVersion: '4', compatible: true, message: '备份可恢复' })
    api.restoreBackup.mockReset().mockResolvedValue({ backupId: backup.id,
      protectionBackupId: '22222222-2222-2222-2222-222222222222', restoredAt: '2026-08-24T12:10:00', status: 'SUCCEEDED' })
  })

  it('creates a backup and refreshes the list', async () => {
    const wrapper = mount(BackupPanel)
    await flushPromises()
    await wrapper.get('[data-testid="create-backup"]').trigger('click')
    await flushPromises()
    expect(api.createBackup).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('备份创建成功')
  })

  it('previews then requires the exact restore confirmation', async () => {
    const wrapper = mount(BackupPanel)
    await flushPromises()
    await wrapper.get(`[data-testid="preview-${backup.id}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('迁移版本 4')
    expect(wrapper.get('[data-testid="confirm-restore"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="restore-text"]').setValue('恢复数据')
    await wrapper.get('[data-testid="confirm-restore"]').trigger('click')
    await flushPromises()
    expect(api.restoreBackup).toHaveBeenCalledWith(backup.id, '恢复数据')
    expect(wrapper.text()).toContain('数据恢复成功')
    expect(wrapper.emitted('restored')).toHaveLength(1)
  })
})
