// ==================== WGS-84 ⇄ GCJ-02 坐标转换（F-06.7 R4，口径 F-06.7.2 R6 修订） ====================
// GCJ-02（国测局坐标系，俗称「火星坐标」）为高德地图原生坐标系。F-06.7.2 R6 坐标真源修正后
// 为不对称转换口径：门店坐标（m13 起 DB 存高德 GCJ-02 真源，与底图同源）进图直用不转换；
// 仅用户浏览器定位（WGS-84）须转 GCJ-02——进图与距离计算都转（R6-2：用户 WGS84 原值与门店
// GCJ 混算 haversine 有 ~500m 系统性偏移，必须同系计算）。偏移算法为国测局标准公式
//（公式公开域，业界通用实现），纯前端计算零网络调用。

// 克拉索夫斯基椭球参数：长半轴 a（米）与第一偏心率平方 e²
const PI = Math.PI
const SEMI_MAJOR_AXIS = 6378245.0
const EE = 0.00669342162296594326

// 纬度偏移辅助函数（标准公式）
function transformLat(x, y) {
	let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
	ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
	ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
	ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
	return ret
}

// 经度偏移辅助函数（标准公式）
function transformLng(x, y) {
	let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
	ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
	ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
	ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
	return ret
}

// 中国境外粗判（标准做法）：经度 72.004~137.8347、纬度 0.8293~55.8271 矩形之外视为境外，
// 境外坐标无 GCJ 偏移，原样返回
function outOfChina(lng, lat) {
	return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

// WGS-84 → GCJ-02：入参 (lng, lat)，返回 { lng, lat }
// 境外坐标（上述矩形之外）原样返回，不做偏移
export function wgs84ToGcj02(lng, lat) {
	if (outOfChina(lng, lat)) return { lng, lat }
	let dLat = transformLat(lng - 105.0, lat - 35.0)
	let dLng = transformLng(lng - 105.0, lat - 35.0)
	const radLat = lat / 180.0 * PI
	let magic = Math.sin(radLat)
	magic = 1 - EE * magic * magic
	const sqrtMagic = Math.sqrt(magic)
	dLat = (dLat * 180.0) / ((SEMI_MAJOR_AXIS * (1 - EE)) / (magic * sqrtMagic) * PI)
	dLng = (dLng * 180.0) / (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(radLat) * PI)
	return { lng: lng + dLng, lat: lat + dLat }
}

// GCJ-02 → WGS-84：单向近似（偏移非线性、无解析逆解，用「在 GCJ 点再取一次偏移量并
// 反向扣减」的通用近似回推，误差厘米~米级）。本页（pickup）当前未使用，供未来
// 「高德地图点选回填 WGS84」类场景取用；不可用于需要精确逆变换的场合。
export function gcj02ToWgs84(lng, lat) {
	if (outOfChina(lng, lat)) return { lng, lat }
	const mg = wgs84ToGcj02(lng, lat)
	return { lng: lng - (mg.lng - lng), lat: lat - (mg.lat - lat) }
}
