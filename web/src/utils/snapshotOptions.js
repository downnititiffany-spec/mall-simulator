const PUBLISHED_STATUSES = new Set(['ACTIVE', 'ARCHIVED'])

/**
 * Build employee-safe choices from published metric snapshots. A choice is one immutable
 * source/business-time/definition snapshot, which prevents dashboards from mixing versions.
 */
export function buildSnapshotOptions(snapshots, sources) {
  const sourceById = new Map((Array.isArray(sources) ? sources : [])
    .filter((source) => source && Number.isSafeInteger(Number(source.sourceId)))
    .map((source) => [Number(source.sourceId), source]))

  return (Array.isArray(snapshots) ? snapshots : [])
    .filter((snapshot) => snapshot
      && PUBLISHED_STATUSES.has(String(snapshot.status || '').toUpperCase())
      && typeof snapshot.snapshotId === 'string'
      && snapshot.snapshotId.trim()
      && Number.isSafeInteger(Number(snapshot.sourceId))
      && Number(snapshot.sourceId) > 0
      && typeof snapshot.businessTime === 'string'
      && snapshot.businessTime.length >= 10)
    .map((snapshot) => {
      const sourceId = Number(snapshot.sourceId)
      const source = sourceById.get(sourceId)
      const sourceName = source?.displayName || source?.sourceCode || `数据源 #${sourceId}`
      const businessDate = snapshot.businessTime.slice(0, 10)
      const status = String(snapshot.status).toUpperCase()
      return {
        snapshotId: snapshot.snapshotId.trim(),
        sourceId,
        sourceName,
        businessDate,
        status,
        definitionVersion: snapshot.definitionVersion || null,
        label: `${sourceName} · ${businessDate} · ${snapshot.snapshotId.trim()} · ${status === 'ACTIVE' ? '当前' : '历史'}`
      }
    })
}

/** Preserve an explicitly saved choice, otherwise default only to ACTIVE (never silently to history). */
export function initialSnapshotId(options, savedSnapshotId) {
  const choices = Array.isArray(options) ? options : []
  if (savedSnapshotId && choices.some((choice) => choice.snapshotId === savedSnapshotId)) {
    return savedSnapshotId
  }
  return choices.find((choice) => choice.status === 'ACTIVE')?.snapshotId || ''
}

export function withSnapshotId(params, snapshotId) {
  return snapshotId ? { ...(params || {}), snapshotId } : { ...(params || {}) }
}
