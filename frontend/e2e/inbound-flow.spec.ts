import { expect, test } from '@playwright/test'

const barcode = '6900000000012'

test('分类先行扫码入库后，库存余额、均价和来源单号一致', async ({ page }) => {
  await page.goto('/catalog')

  const categorySection = page.locator('section[aria-labelledby="categories-title"]')
  await categorySection.getByLabel('分类名称').first().fill('球类用品')
  await categorySection.getByRole('button', { name: '新增分类' }).click()
  await expect(categorySection.getByLabel('分类名称')).toHaveCount(2)
  await expect(categorySection.getByLabel('分类名称').nth(1)).toHaveValue('球类用品')

  const brandSection = page.locator('section[aria-labelledby="brands-title"]')
  await brandSection.getByLabel('品牌名称').first().fill('端到端测试品牌')
  await brandSection.getByRole('button', { name: '新增品牌' }).click()
  await expect(brandSection.getByLabel('品牌名称')).toHaveCount(2)
  await expect(brandSection.getByLabel('品牌名称').nth(1)).toHaveValue('端到端测试品牌')

  await page.goto('/inbounds')
  await page.getByLabel('当前分类').selectOption({ label: '球类用品' })
  await page.getByTestId('barcode-input').fill(barcode)
  await page.getByTestId('scan-submit').click()

  const quickCreate = page.getByTestId('quick-create-dialog')
  await expect(quickCreate.getByTestId('quick-category')).toHaveValue('球类用品')
  await expect(quickCreate.getByTestId('quick-barcode')).toHaveValue(barcode)
  await quickCreate.getByTestId('quick-product-name').fill('训练篮球')
  await quickCreate.getByTestId('quick-sku-code').fill('BASKETBALL-E2E-01')
  await quickCreate.getByTestId('quick-retail-price').fill('159')
  await quickCreate.getByTestId('quick-save').click()

  await page.getByTestId('pending-quantity').fill('10')
  await page.getByTestId('pending-unit-cost').fill('100')
  await page.getByTestId('add-line').click()
  await page.getByTestId('confirm-inbound').click()

  const success = page.getByRole('status')
  await expect(success).toContainText('入库成功，单号：')
  const orderNo = (await success.locator('strong').innerText()).trim()
  expect(orderNo).toMatch(/^IN-\d{8}-\d{6}$/)

  await page.goto('/inventory')
  await expect(page.getByRole('heading', { name: '库存管理' })).toBeVisible()
  const inventoryRow = page.getByRole('row').filter({ hasText: barcode })
  await expect(inventoryRow.locator('td').nth(4)).toHaveText('10')
  await expect(inventoryRow.locator('td').nth(5)).toHaveText('100.0000')

  await inventoryRow.getByRole('button', { name: '查看流水' }).click()
  const movementDialog = page.getByRole('dialog', { name: '库存流水' })
  await expect(movementDialog).toContainText(orderNo)
  await expect(movementDialog.getByRole('row').filter({ hasText: orderNo })).toContainText('+10')
})
