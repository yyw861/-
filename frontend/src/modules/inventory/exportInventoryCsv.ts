import type { InventoryItem } from './types'

const headings = ['商品名称', 'SKU编码', '条码', '分类', '品牌', '库存数量', '预警库存', '平均成本', '库存金额']

function cell(value: string | number) {
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

function textCell(value: string) {
  const safe = /^[=+\-@]/.test(value) ? `'${value}` : value
  return cell(safe)
}

export function inventoryCsv(items: InventoryItem[]) {
  const rows = items.map(item => [textCell(item.productName), textCell(item.skuCode),
    textCell(`'${item.barcode}`), textCell(item.categoryName), textCell(item.brandName),
    cell(item.quantity), cell(item.warningStock), cell(Number(item.averageCost).toFixed(4)),
    cell(Number(item.inventoryValue).toFixed(4))].join(','))
  return `\uFEFF${headings.join(',')}\r\n${rows.join('\r\n')}`
}

export function downloadInventoryCsv(items: InventoryItem[]) {
  const blob = new Blob([inventoryCsv(items)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `inventory-${new Date().toISOString().slice(0, 10)}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}
