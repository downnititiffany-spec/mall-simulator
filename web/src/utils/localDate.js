// HTML date inputs represent a local calendar day, not a UTC instant.
// Never derive their default value through Date#toISOString(), because around
// local midnight that can select yesterday/tomorrow for users outside UTC.
export function localIsoDay(date = new Date()) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function localIsoDayOffset(offsetDays, now = new Date()) {
  const date = new Date(now.getTime())
  date.setDate(date.getDate() + Number(offsetDays || 0))
  return localIsoDay(date)
}
