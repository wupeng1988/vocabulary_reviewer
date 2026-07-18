<template>
  <div>
    <!-- 顶部统计卡片 -->
    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="词库总量" :value="stats.totalWords || 0">
            <template #prefix>📚</template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="今日待复习" :value="stats.todayReviewCount || 0">
            <template #prefix>⏰</template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="整体正确率" :value="stats.overallAccuracy || 0" suffix="%">
            <template #prefix>🎯</template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="长期未复习" :value="stats.longUnreviewedCount || 0">
            <template #prefix>💤</template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>

    <!-- 正确率对比 -->
    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="认读正确率" :value="stats.recogAccuracy || 0" suffix="%">
            <template #prefix>📖</template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="默写正确率" :value="stats.spellAccuracy || 0" suffix="%">
            <template #prefix>✍️</template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区 -->
    <el-row :gutter="20">
      <!-- 易错词 Top 5 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <span style="font-weight: bold">🔥 易错词 Top 5</span>
          </template>
          <div ref="errorChartRef" style="height: 350px"></div>
        </el-card>
      </el-col>

      <!-- 正确率对比图 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <span style="font-weight: bold">📊 认读 vs 默写 正确率</span>
          </template>
          <div ref="accuracyChartRef" style="height: 350px"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import api from '../api'

const stats = ref({})
const errorChartRef = ref(null)
const accuracyChartRef = ref(null)

let errorChart = null
let accuracyChart = null

const loadStats = async () => {
  try {
    const res = await api.get('/stats/dashboard')
    stats.value = res
    await nextTick()
    renderCharts()
  } catch (e) {
    console.error('加载统计数据失败', e)
  }
}

const renderCharts = () => {
  renderErrorChart()
  renderAccuracyChart()
}

const renderErrorChart = () => {
  if (!errorChartRef.value) return
  if (!errorChart) {
    errorChart = echarts.init(errorChartRef.value)
  }

  const topErrors = stats.value.topErrorWords || []
  const words = topErrors.map(w => w.word)
  const errorRates = topErrors.map(w => w.errorRate)

  errorChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: '{b}: {c}%'
    },
    grid: { left: 80, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    yAxis: { type: 'category', data: words.reverse(), axisLabel: { fontSize: 14 } },
    series: [{
      type: 'bar',
      data: errorRates.reverse(),
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
          { offset: 0, color: '#F56C6C' },
          { offset: 1, color: '#E6A23C' }
        ])
      },
      barWidth: 25,
      label: {
        show: true,
        position: 'right',
        formatter: '{c}%'
      }
    }]
  })
}

const renderAccuracyChart = () => {
  if (!accuracyChartRef.value) return
  if (!accuracyChart) {
    accuracyChart = echarts.init(accuracyChartRef.value)
  }

  accuracyChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { top: '5%', left: 'center' },
    series: [
      {
        name: '认读正确率',
        type: 'pie',
        center: ['30%', '55%'],
        radius: ['0%', '60%'],
        data: [
          { value: stats.value.recogAccuracy || 0, name: '正确', itemStyle: { color: '#67C23A' } },
          { value: 100 - (stats.value.recogAccuracy || 0), name: '错误', itemStyle: { color: '#F56C6C' } }
        ],
        label: { formatter: '{b}\n{d}%' }
      },
      {
        name: '默写正确率',
        type: 'pie',
        center: ['70%', '55%'],
        radius: ['0%', '60%'],
        data: [
          { value: stats.value.spellAccuracy || 0, name: '正确', itemStyle: { color: '#409EFF' } },
          { value: 100 - (stats.value.spellAccuracy || 0), name: '错误', itemStyle: { color: '#E6A23C' } }
        ],
        label: { formatter: '{b}\n{d}%' }
      }
    ]
  })
}

onMounted(() => {
  loadStats()
})
</script>
