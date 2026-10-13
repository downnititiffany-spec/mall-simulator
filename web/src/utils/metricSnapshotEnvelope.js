/** Copy the envelope's pinned snapshot identity onto its metric rows for legacy table/export fields. */
export function withMetricSnapshotIdentity(envelope) {
  if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) return envelope

  const data = envelope.data
  if (!data || typeof data !== 'object' || Array.isArray(data) || !Array.isArray(data.metrics)) {
    return envelope
  }

  const metrics = data.metrics.map((metric) => {
    if (!metric || typeof metric !== 'object' || Array.isArray(metric)) return metric
    return envelope.snapshotId
      ? { ...metric, snapshotId: envelope.snapshotId }
      : { ...metric }
  })
  return { ...envelope, data: { ...data, metrics } }
}
