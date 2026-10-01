import { reactive } from 'vue'
import api from '../api'
import { buildSnapshotOptions, initialSnapshotId } from '../utils/snapshotOptions'

export const snapshotSelection = reactive({
  options: [],
  selectedSnapshotId: '',
  loading: false,
  initialized: false,
  error: '',
  sourceLabelWarning: ''
})

let loadingPromise = null

function currentUserKey() {
  try {
    const user = JSON.parse(localStorage.getItem('analytics_user') || 'null')
    const identity = user && (user.id || user.username)
    return identity ? `analytics_selected_snapshot:${encodeURIComponent(String(identity))}` : null
  } catch (_) {
    return null
  }
}

function readSavedSnapshotId() {
  const key = currentUserKey()
  return key ? localStorage.getItem(key) : null
}

function saveSnapshotId(snapshotId) {
  const key = currentUserKey()
  if (!key) return
  if (snapshotId) localStorage.setItem(key, snapshotId)
  else localStorage.removeItem(key)
}

export async function ensureSnapshotSelection(force = false) {
  if (snapshotSelection.initialized && !force) return snapshotSelection
  if (loadingPromise) return loadingPromise

  snapshotSelection.loading = true
  snapshotSelection.error = ''
  snapshotSelection.sourceLabelWarning = ''
  loadingPromise = (async () => {
    try {
      const snapshots = await api.snapshots(100)
      let sources = []
      try {
        sources = await api.analyticsSourceOptions()
      } catch (_) {
        snapshotSelection.sourceLabelWarning = '数据源名称暂不可用，当前以来源编号显示。'
      }

      snapshotSelection.options = buildSnapshotOptions(snapshots, sources)
      const selected = initialSnapshotId(snapshotSelection.options, readSavedSnapshotId())
      snapshotSelection.selectedSnapshotId = selected
      saveSnapshotId(selected)
      if (!selected) snapshotSelection.error = '暂无可用的已发布数据快照；请联系管理员完成数据发布。'
      snapshotSelection.initialized = true
      return snapshotSelection
    } catch (error) {
      snapshotSelection.error = (error && (error.message || error.code)) || '读取已发布快照失败'
      snapshotSelection.initialized = false
      throw error
    } finally {
      snapshotSelection.loading = false
      loadingPromise = null
    }
  })()
  return loadingPromise
}

export function selectSnapshot(snapshotId) {
  const choice = snapshotSelection.options.find((item) => item.snapshotId === snapshotId)
  if (!choice) throw new Error('所选快照不在已发布选项中，已拒绝切换。')
  snapshotSelection.selectedSnapshotId = choice.snapshotId
  saveSnapshotId(choice.snapshotId)
  snapshotSelection.error = ''
  return choice
}

/** Reset in-memory state across logout/login; per-user saved selection remains namespaced. */
export function resetSnapshotSelection() {
  snapshotSelection.options = []
  snapshotSelection.selectedSnapshotId = ''
  snapshotSelection.loading = false
  snapshotSelection.initialized = false
  snapshotSelection.error = ''
  snapshotSelection.sourceLabelWarning = ''
  loadingPromise = null
}
