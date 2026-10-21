// ==================== WGS-84 ⇄ GCJ-02 坐标转换（F-18.19 §3 条件项：本文件是第 2 份副本） ====================
// 来源与纪律：代码体逐字拷自 front/utils/gcj02.js（F-06.7 R4 / F-06.7.2 R6 定稿那一份），
// 拷而不是引——C 端目录（front/**）按 RA 红线一个字节都不改，跨端 import 又会把主理人端
// 绑到 C 端的构建产物上，故留副本并在此登记为债务：**两份副本此后必须同步改，改一处就要改两处**
// （抽公共包属 npm 依赖，F-18.19 §1 明令不引；真解在 G-05 之后与 C 端同一次动刀时收口）。
//
// 口径（不对称转换，HANDOFF §8）：DB 存 GCJ-02 真源——store.lng/lat 与 store_apply.lng/lat 都是
// DECIMAL(10,6) 的「高德 GCJ-02」值（backEnd/sql/init.sql:178-179 store 与 :439-440 store_apply 两处列注释），进图与distance计算直用不转换；
// **只有用户设备定位那一侧是 WGS-84**，落到这两列之前必须转成 GCJ-02，否则与门店坐标不同系
// （R6-2 实测混算有 ~500m 系统性偏移）。
//
// 为什么主理人端也要这一份：uni.getLocation 在 H5 端 request type:'gcj02' 并不做本地数学转换，
// 而是把转换委托给地图 SDK（见 pages/apply/apply.vue 里 locatePosition() 的引证与行号），
// 本工程按 D-24 一期不配高德，那条路会直接 reject。故本页取 type:'wgs84'（原生返回、无需 SDK）
// 再在本文件里本地转 GCJ-02。
//
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

// GCJ-02 → WGS-84：单向近似（偏移非线性、无解析反解，用「在 GCJ 点再取一次偏移量并
// 反向扣减」的通用近似回推，误差厘米~米级）。本页（apply）当前未使用，供未来
// 「高德地图点选回填 WGS84」类场景取用（D-24 之后 G-05 的地图选点）；不可用于需要精确反变换的场合。
export function gcj02ToWgs84(lng, lat) {
	if (outOfChina(lng, lat)) return { lng, lat }
	const mg = wgs84ToGcj02(lng, lat)
	return { lng: lng - (mg.lng - lng), lat: lat - (mg.lat - lat) }
}
