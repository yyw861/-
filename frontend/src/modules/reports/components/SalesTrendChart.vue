<script setup lang="ts">
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SalesTrendPoint } from '../types'
import { formatCurrency } from '../currency'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ points: SalesTrendPoint[] }>()
const root = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
function render() { if (!chart) return; chart.setOption({ tooltip:{trigger:'axis',valueFormatter:(value: unknown)=>formatCurrency(Number(value))}, legend:{data:['净销售额','毛利']}, grid:{left:80,right:20,bottom:35}, xAxis:{type:'category',data:props.points.map(p=>p.date)}, yAxis:{type:'value',axisLabel:{formatter:(value:number)=>formatCurrency(value)}}, series:[{name:'净销售额',type:'line',smooth:true,data:props.points.map(p=>Number(p.netSalesAmount))},{name:'毛利',type:'line',smooth:true,data:props.points.map(p=>Number(p.grossProfit))}] }, true) }
onMounted(()=>{if(root.value){chart=echarts.init(root.value);render()}})
watch(()=>props.points,render,{deep:true})
onBeforeUnmount(()=>chart?.dispose())
</script>
<template><div ref="root" class="chart" role="img" aria-label="销售趋势图"></div><ul class="sr-only"><li v-for="point in points" :key="point.date">{{point.date}}：净销售额 {{formatCurrency(point.netSalesAmount)}}，毛利 {{formatCurrency(point.grossProfit)}}</li></ul></template>
<style scoped>.chart{height:20rem;width:100%}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}</style>
