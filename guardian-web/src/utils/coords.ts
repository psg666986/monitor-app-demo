/**
 * WGS-84 → GCJ-02（火星坐标）坐标转换。
 *
 * Android FusedLocationProvider 输出 WGS-84；高德地图使用 GCJ-02。
 * 中国大陆范围外的坐标直接原样返回（转换算法仅适用于中国大陆）。
 */

const PI  = Math.PI
const A   = 6378245.0          // 克拉索夫斯基椭球长半轴
const EE  = 0.00669342162296594323  // 第一偏心率平方

function outOfChina(lng: number, lat: number): boolean {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLat(x: number, y: number): number {
  let r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  r += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3
  r += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3
  return r
}

function transformLng(x: number, y: number): number {
  let r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  r += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3
  r += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3
  return r
}

/**
 * 将 WGS-84 坐标转换为高德地图使用的 GCJ-02 坐标。
 * @returns [经度, 纬度]（GCJ-02）
 */
export function wgs84ToGcj02(lng: number, lat: number): [number, number] {
  if (outOfChina(lng, lat)) return [lng, lat]

  let dLat = transformLat(lng - 105, lat - 35)
  let dLng = transformLng(lng - 105, lat - 35)

  const radLat  = (lat / 180) * PI
  let   magic   = Math.sin(radLat)
  magic = 1 - EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)

  dLat = (dLat * 180) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
  dLng = (dLng * 180) / (A / sqrtMagic * Math.cos(radLat) * PI)

  return [lng + dLng, lat + dLat]
}
