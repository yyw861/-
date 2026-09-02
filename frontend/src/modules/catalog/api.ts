import axios from 'axios'

import { http } from '@/shared/api/http'
import type {
  Brand, Category, Page, Product, ProductUpdateRequest, QuickCreateSkuRequest, Sku, SkuUpdateRequest, SubCategory,
} from './types'

export async function getCategories(): Promise<Category[]> {
  return (await http.get<Category[]>('/categories')).data
}

export async function createCategory(code: string, name: string): Promise<Category> {
  return (await http.post<Category>('/categories', { code, name })).data
}

export async function getSubCategories(categoryId: string): Promise<SubCategory[]> {
  return (await http.get<SubCategory[]>(`/categories/${categoryId}/subcategories`)).data
}

export async function createSubCategory(categoryId: string, code: string, name: string): Promise<SubCategory> {
  return (await http.post<SubCategory>(`/categories/${categoryId}/subcategories`, { code, name })).data
}

export async function updateSubCategory(categoryId: string, id: string, patch: Partial<Omit<SubCategory, 'id' | 'categoryId'>>): Promise<SubCategory> {
  return (await http.patch<SubCategory>(`/categories/${categoryId}/subcategories/${id}`, patch)).data
}

export async function findCategoryByPrefix(prefix: string): Promise<Category> {
  return (await http.get<Category>(`/catalog/categories/by-prefix/${encodeURIComponent(prefix)}`)).data
}

export async function updateCategory(id: string, patch: Partial<Omit<Category, 'id'>>): Promise<Category> {
  return (await http.patch<Category>(`/categories/${id}`, patch)).data
}

export async function getBrands(): Promise<Brand[]> {
  return (await http.get<Brand[]>('/brands')).data
}

export async function createBrand(name: string): Promise<Brand> {
  return (await http.post<Brand>('/brands', { name })).data
}

export async function updateBrand(id: string, patch: Partial<Omit<Brand, 'id'>>): Promise<Brand> {
  return (await http.patch<Brand>(`/brands/${id}`, patch)).data
}

export async function getProducts(page = 0, size = 100): Promise<Page<Product>> {
  return (await http.get<Page<Product>>('/catalog/products', { params: { page, size } })).data
}

export async function getProduct(id: string): Promise<Product> {
  return (await http.get<Product>(`/catalog/products/${id}`)).data
}

export async function createProduct(request: Omit<ProductUpdateRequest, 'enabled'>): Promise<Product> {
  return (await http.post<Product>('/catalog/products', request)).data
}

export async function updateProduct(id: string, request: ProductUpdateRequest): Promise<Product> {
  return (await http.patch<Product>(`/catalog/products/${id}`, request)).data
}

export async function findSkuByBarcode(barcode: string): Promise<Sku> {
  return (await http.get<Sku>(`/catalog/skus/by-barcode/${encodeURIComponent(barcode)}`)).data
}

export async function quickCreateSku(request: QuickCreateSkuRequest): Promise<Sku> {
  return (await http.post<Sku>('/catalog/skus/quick-create', request)).data
}

export async function updateSku(id: string, request: SkuUpdateRequest): Promise<Sku> {
  return (await http.patch<Sku>(`/catalog/skus/${id}`, request)).data
}

export async function setSkuEnabled(id: string, enabled: boolean): Promise<void> {
  await http.patch(`/catalog/skus/${id}/enabled`, { enabled })
}

export function isNotFound(error: unknown): boolean {
  return axios.isAxiosError(error) ? error.response?.status === 404 :
    typeof error === 'object' && error !== null &&
      'response' in error && (error as { response?: { status?: number } }).response?.status === 404
}

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError<{ detail?: string }>(error)) return error.response?.data?.detail ?? error.message
  return error instanceof Error ? error.message : '操作失败，请重试'
}
