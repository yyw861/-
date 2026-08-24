import { h } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => h('main', '首页'),
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: () => import('@/modules/catalog/views/CatalogView.vue'),
    },
    {
      path: '/inbounds',
      name: 'inbound',
      component: () => import('@/modules/inbound/views/InboundView.vue'),
    },
    {
      path: '/inbounds/history',
      name: 'inbound-history',
      component: () => import('@/modules/inbound/views/InboundHistoryView.vue'),
    },
    {
      path: '/inventory',
      name: 'inventory',
      component: () => import('@/modules/inventory/views/InventoryView.vue'),
    },
    {
      path: '/sales',
      name: 'sales-checkout',
      component: () => import('@/modules/sales/views/CheckoutView.vue'),
    },
    {
      path: '/sales/history',
      name: 'sales-history',
      component: () => import('@/modules/sales/views/SalesHistoryView.vue'),
    },
    {
      path: '/sales/:id',
      name: 'sale-detail',
      component: () => import('@/modules/sales/views/SaleDetailView.vue'),
    },
    {
      path: '/returns',
      redirect: '/sales/history',
    },
  ],
})

export default router
