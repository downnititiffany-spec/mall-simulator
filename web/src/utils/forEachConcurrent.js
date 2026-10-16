/**
 * Run async work from a list with a fixed worker ceiling.
 * Worker errors propagate to the caller; callers that support partial results
 * should catch item-level errors inside the worker.
 */
export async function forEachConcurrent(items, requestedConcurrency, worker) {
  const rows = Array.isArray(items) ? items : []
  if (rows.length === 0) return
  const numericConcurrency = Number(requestedConcurrency)
  const concurrency = Number.isFinite(numericConcurrency)
    ? Math.min(rows.length, Math.max(1, Math.floor(numericConcurrency)))
    : 1
  let nextIndex = 0
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (nextIndex < rows.length) {
      const index = nextIndex++
      await worker(rows[index], index)
    }
  }))
}
