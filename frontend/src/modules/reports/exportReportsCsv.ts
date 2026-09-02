import type { ProductRanking } from './types'

function cell(value: string | number) {
  const text = String(value)
  const safe = typeof value === 'string' && /^[=+\-@]/.test(text) ? `'${text}` : text
  return /[",\r\n]/.test(safe) ? `"${safe.replaceAll('"', '""')}"` : safe
}

export function rankingCsv(items: ProductRanking[]) {
  const rows = items.map(item => [item.productName, item.categoryCode, item.categoryName, item.subCategoryCode, item.subCategoryName, item.skuCode, `'${item.barcode}`, item.grossQuantity,
    item.returnedQuantity, item.netQuantity, Number(item.netSalesAmount).toFixed(2)].map(cell).join(','))
  return `\uFEFF商品名称,大类编号,大类名称,小类编号,小类名称,SKU编码,条码,销售数量,退货数量,净销量,净销售额\r\n${rows.join('\r\n')}`
}

export function downloadRankingCsv(items: ProductRanking[], fromDate: string, toDate: string) {
  const url = URL.createObjectURL(new Blob([rankingCsv(items)], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `product-ranking-${fromDate}-${toDate}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}
