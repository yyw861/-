export type MoneyValue = string | number

const cny = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

export function formatCurrency(value: MoneyValue) {
  return cny.format(Number(value))
}
