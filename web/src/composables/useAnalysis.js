// 分析页统一取数 composable：信封解包 + 四态 + 切换筛选取消旧请求
// 对应契约 §4 与指导书 §18.3 / §18.4。
// 设计要点：
// 1) 每次 load 递增请求序号，只有最新序号的响应才允许写入状态（慢响应不覆盖新数据）；
// 2) 同时用 AbortController 取消上一个未完成请求，避免无谓等待；
// 3) 旧数据在屏时进入 stale（“数据更新中”），此时禁止导出，防止导出错版数据。
import { computed, onServerPrefetch, ref, getCurrentInstance, watch } from 'vue'
import { readEnvelope } from '../utils/envelope'
import { REQUEST, chartState, canExport, stateText, rowCount } from '../utils/chartState'
import { withSnapshotId } from '../utils/snapshotOptions'
import { ensureSnapshotSelection, snapshotSelection } from './useSnapshotSelection'

export { REQUEST }

export function useAnalysis({ fetcher, rowKeys = [], defaults = [], pinSnapshot = false }) {
  const data = ref(defaults)
  const context = ref(null)
  const requestStatus = ref(REQUEST.IDLE)
  const error = ref('')

  // 请求序号守卫：防止慢响应覆盖新数据
  let seq = 0
  let controller = null
  let lastRequestParams = {}

  const pointCount = computed(() => rowCount(data.value, rowKeys))
  const state = computed(() => chartState(requestStatus.value, pointCount.value))
  const loading = computed(() => requestStatus.value === REQUEST.LOADING)
  const stale = computed(() => state.value === 'stale')
  const ready = computed(() => state.value === 'ready')
  const empty = computed(() => state.value === 'empty')
  const failed = computed(() => state.value === 'error')
  const exportable = computed(() => canExport(state.value))
  const statusText = computed(() => stateText(state.value))

  // 导出上下文：`filters` **只**取信封回显（后端回显的**生效筛选**，契约
  // `docs/contracts/analysis-viewmodel-r7-4.md:182`）—— `load` 不保存请求参数，
  // 信封缺 `filters` 时按**缺失**处理（导出件不写该行），因此**不**回退到本次请求参数；
  // 副作用是 `@click="load"` 传进来的 MouseEvent 也不会污染它的内容。
  // S3-26：带上信封 `source`（发布方），导出件才能自证“数据由谁发布”（指导书阶段5 L164）
  const exportContext = computed(() => {
    const ctx = context.value || {}
    return {
      snapshotId: ctx.snapshotId || null,
      // sourceId 是业务来源 source_registry.id；与 source（指标快照发布方）分字段导出。
      // null 必须保持 null，不能由当前选源或发布方推断。
      sourceId: ctx.sourceId ?? null,
      businessTime: ctx.businessTime || null,
      dataUpdatedAt: ctx.dataUpdatedAt || null,
      source: ctx.source || null,
      definitionVersion: ctx.definitionVersion || null,
      qualityStatus: ctx.qualityStatus || 'UNKNOWN',
      filters: ctx.filters && Object.keys(ctx.filters).length ? ctx.filters : null,
      warnings: ctx.warnings || [],
      // S3-35：缺失告知随上下文一起给页横幅与导出件（csv.js 写「# 上下文缺失」）；
      // 统一信封接口没有该字段时为 null，横幅不显示、导出不写该行。
      missingNotice: ctx.missingNotice || null,
      // 下载层不能只信任按钮 disabled：把当前四态一起交给 exportCsv 做二次 fail-closed。
      // 这样程序化调用 handler、快速刷新窗口或非统一信封页面也不会导出 stale/error/loading 数据。
      viewState: state.value
    }
  })

  /**
   * 取数。fetcher(requestParams, signal) 返回 api 层已剥掉 ApiResponse 外壳的 data。
   */
  async function load(requestParams = {}) {
    const mySeq = ++seq
    if (controller) controller.abort()
    controller = typeof AbortController === 'function' ? new AbortController() : null
    // 若屏幕已有数据，保留旧数据并标记 stale；否则进入 loading
    requestStatus.value = REQUEST.LOADING
    error.value = ''
    try {
      lastRequestParams = { ...requestParams }
      delete lastRequestParams.snapshotId
      let effectiveParams = { ...requestParams }
      if (pinSnapshot) {
        await ensureSnapshotSelection()
        const snapshotId = snapshotSelection.selectedSnapshotId
        if (!snapshotId) {
          throw new Error(snapshotSelection.error || '没有可用的已发布快照')
        }
        effectiveParams = withSnapshotId(effectiveParams, snapshotId)
      }
      if (mySeq !== seq) return null
      const raw = await fetcher(effectiveParams, controller ? controller.signal : undefined)
      if (mySeq !== seq) return null // 已有更新的请求，丢弃本次结果
      const ctx = readEnvelope(raw)
      context.value = ctx
      data.value = ctx.data
      requestStatus.value = REQUEST.READY
      return ctx
    } catch (e) {
      if (mySeq !== seq) return null
      const aborted = e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError')
      if (aborted) return null // 主动取消不算失败，也不改状态
      error.value = (e && (e.message || e.code)) || '请求失败'
      requestStatus.value = REQUEST.ERROR
      return null
    }
  }

  /**
   * 组件卸载时调用：取消在途请求，避免卸载后写入状态
   */
  function cancel() {
    seq += 1
    if (controller) controller.abort()
    controller = null
  }

  if (pinSnapshot) {
    watch(() => snapshotSelection.selectedSnapshotId, (next, previous) => {
      // 初次从空值初始化默认 ACTIVE 时，当前 load 已等待选项加载并会使用该快照；
      // 只有员工随后主动切换快照才需要重载当前页面。
      if (!previous || !next || next === previous) return
      void load(lastRequestParams)
    })
  }

  // SSR 预取：仅在没有浏览器环境时启用，让同一 composable 在服务端渲染/离线渲染校验中也能拿到数据。
  // 浏览器端（存在 window）不注册该钩子，运行行为与改造前完全一致。
  if (typeof window === 'undefined' && getCurrentInstance()) {
    onServerPrefetch(() => load({}))
  }

  return {
    data,
    context,
    requestStatus,
    error,
    state,
    loading,
    stale,
    ready,
    empty,
    failed,
    exportable,
    statusText,
    exportContext,
    load,
    cancel
  }
}
