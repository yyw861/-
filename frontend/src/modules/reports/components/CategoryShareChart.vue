<script setup lang="ts">
import { PieChart } from 'echarts/charts'; import { LegendComponent, TooltipComponent } from 'echarts/components'; import * as echarts from 'echarts/core'; import { CanvasRenderer } from 'echarts/renderers'; import { onBeforeUnmount,onMounted,ref,watch } from 'vue'; import type { CategoryShare } from '../types'; import { formatCurrency } from '../currency'
echarts.use([PieChart,LegendComponent,TooltipComponent,CanvasRenderer]);const props=defineProps<{items:CategoryShare[]}>();const root=ref<HTMLDivElement>();let chart:echarts.ECharts|undefined
function render(){chart?.setOption({tooltip:{trigger:'item',valueFormatter:(value:unknown)=>formatCurrency(Number(value))},legend:{bottom:0},series:[{name:'分类销售占比',type:'pie',radius:['38%','68%'],data:props.items.map(item=>({name:item.categoryName,value:Number(item.netSalesAmount)}))}]},true)}
onMounted(()=>{if(root.value){chart=echarts.init(root.value);render()}});watch(()=>props.items,render,{deep:true});onBeforeUnmount(()=>chart?.dispose())
</script>
<template><div ref="root" class="chart" role="img" aria-label="分类销售占比图"></div><ul class="sr-only"><li v-for="item in items" :key="item.categoryId">{{item.categoryName}}：{{formatCurrency(item.netSalesAmount)}}</li></ul></template>
<style scoped>.chart{height:20rem;width:100%}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}</style>
