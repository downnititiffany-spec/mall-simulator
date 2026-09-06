<template>
  <div>
    <div class="page-title">智能分析助手</div>
    <div class="chart-box">
      <div class="chart-title">自然语言问数（阶段 8 接入：语义层 → Text-to-SQL → 安全校验 → 证据解释）</div>
      <div style="display:flex;gap:10px">
        <input v-model="question" placeholder="例如：最近 7 天销售额变化趋势如何？" style="flex:1;padding:8px" />
        <button style="padding:8px 18px;background:#7c3aed;color:#fff;border:none;border-radius:6px" disabled>
          发送（敬请期待）
        </button>
      </div>
      <div class="el-empty" style="margin-top:12px">
        本模块将在下一阶段上线：输入问题 → 系统识别指标/时间 → 生成受约束 SQL →
        安全校验 → 查询真实数据 → 证据链解释。
      </div>
      <div style="font-size:13px;color:#6b7280;line-height:1.8">
        推荐问题（阶段 8 支持）：
        <ul style="margin-top:6px;padding-left:18px">
          <li>最近 7 天销售额的变化趋势如何？</li>
          <li>哪些商品的浏览量高但购买转化率低？</li>
          <li>最近一天的销售额是否明显偏离近 7 日平均水平？</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
const question = ref('')
</script>