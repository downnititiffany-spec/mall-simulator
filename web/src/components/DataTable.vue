<script setup>
// 通用结果表：只按 [表头, 行] 渲染，不做任何字段推断与数值加工。
// 表头与行都由 utils/tables.js 的 toTable() 生成，保证「页面看到的 = 导出 CSV 里的」。
const props = defineProps({
  // toTable() 的返回值：{ headers: string[], rows: (string|number)[][] }
  table: { type: Object, required: true },
  // 无数据时的提示文案（如实说明是接口未返回还是筛选无结果）
  emptyText: { type: String, default: '暂无数据或该接口未返回数据' },
  // 行高亮判定：返回 true 的行加浅蓝底（用于「当前选中的快照」这类状态）
  isActive: { type: Function, default: null },
  // 行级操作按钮（可选）：按钮点击时回调 (rowArray, index)，由调用方决定用哪一列做参数
  actionLabel: { type: String, default: '' },
  onAction: { type: Function, default: null },
  // 单元格文字着色（可选）：回调 (cellValue, rowIndex, colIndex)，只改颜色不改文字
  cellColor: { type: Function, default: null }
})
</script>

<template>
  <div>
    <table v-if="table && table.rows && table.rows.length" class="data-table">
      <thead>
        <tr>
          <th v-for="(h, i) in table.headers" :key="i">{{ h }}</th>
          <th v-if="actionLabel">{{ actionLabel }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in table.rows" :key="i" :class="{ 'row-active': isActive ? isActive(i) : false }">
          <td v-for="(cell, j) in row" :key="j" class="mono"
              :style="cellColor ? { color: cellColor(cell, i, j) } : null">{{ cell === null || cell === undefined ? '—' : cell }}</td>
          <td v-if="actionLabel">
            <button style="font-size:12px" @click="props.onAction && props.onAction(row, i)">查看指标</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="el-empty">{{ emptyText }}</div>
  </div>
</template>

<style scoped>
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.data-table th {
  text-align: left;
  padding: 6px;
  color: var(--color-muted-foreground, #6b7280);
  font-weight: 500;
  border-bottom: 1px solid #e5e7eb;
}
.data-table td {
  padding: 6px;
  border-top: 1px solid #f3f4f6;
}
.data-table tr.row-active td {
  background: #eff6ff;
}
</style>
