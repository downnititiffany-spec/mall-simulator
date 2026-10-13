export function buildReportRequest({ templateCode, snapshot, decisionId, fromDate }) {
  const request = {
    templateCode,
    templateVersion: '1',
    includeAiNarrative: false
  }

  if (templateCode === 'DECISION_EFFECT') {
    request.decisionId = Number(decisionId)
    return request
  }

  request.snapshotId = snapshot.snapshotId
  request.businessDate = snapshot.businessDate
  if (templateCode === 'OPS_WEEKLY') {
    request.fromDate = fromDate
    request.toDate = snapshot.businessDate
  }
  return request
}
