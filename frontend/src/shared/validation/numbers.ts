const MAX_JAVA_INTEGER = 2_147_483_647
const MIN_JAVA_INTEGER = -2_147_483_648

export function isNonNegativeMoney(value: string | number): boolean {
  const text = String(value).trim()
  return /^\d+(?:\.\d{1,2})?$/.test(text) && Number.isFinite(Number(text))
}

export function isNonNegativeInteger(value: string | number): boolean {
  const text = String(value).trim()
  return /^\d+$/.test(text) && Number(text) <= MAX_JAVA_INTEGER
}

export function isJavaInteger(value: string | number): boolean {
  const text = String(value).trim()
  const parsed = Number(text)
  return /^-?\d+$/.test(text) && parsed >= MIN_JAVA_INTEGER && parsed <= MAX_JAVA_INTEGER
}
