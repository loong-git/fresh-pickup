// ==================== 用户头像工具（F-06.7.4 R8） ====================
// DEFAULT_AVATAR：默认头像（品牌渐变圆底 + 白色人形剪影）——登录用户未设置头像与游客共用；
// URL-encoded SVG data URI（encodeURIComponent 全量编码防 # 等字符截断 data URI）。
// fileToAvatarDataURI：H5 选图后 canvas 中心裁方 128×128 输出 JPEG data URI（约 5~30KB，
// 远低于后端 PUT /api/user/avatar 的 60000 字节上限（UTF-8，AVATAR_MAX_BYTES）与 user.avatar TEXT 列容量）。

const AVATAR_SVG = [
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">',
  '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">',
  '<stop offset="0" stop-color="#FF7E5F"/><stop offset="1" stop-color="#E02020"/>',
  '</linearGradient></defs>',
  '<rect width="64" height="64" fill="url(#g)"/>',
  // 人形剪影：头（圆）+ 肩（半椭圆），纯白，水平居中，底边超出画布制造"胸像"裁切感
  '<circle cx="32" cy="25" r="10" fill="#ffffff"/>',
  '<path d="M12 58 Q12 40 32 40 Q52 40 52 58 Z" fill="#ffffff"/>',
  '</svg>'
].join('');

export const DEFAULT_AVATAR = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(AVATAR_SVG)

/**
 * H5 选图 → 头像 data URI：中心裁方 128×128、JPEG 0.85 压缩。
 * file 校验非图片类型直接 reject（不 toast，调用方统一提示）。
 */
export function fileToAvatarDataURI(file) {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !file || !/^image\//.test(file.type || '')) {
      reject(new Error('请选择图片文件'))
      return
    }
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      try {
        const size = 128
        const canvas = document.createElement('canvas')
        canvas.width = size
        canvas.height = size
        const ctx = canvas.getContext('2d')
        // 中心裁方：取原图短边为边长的中央正方形，等比铺满 128×128
        const side = Math.min(img.naturalWidth, img.naturalHeight)
        const sx = (img.naturalWidth - side) / 2
        const sy = (img.naturalHeight - side) / 2
        ctx.drawImage(img, sx, sy, side, side, 0, 0, size, size)
        URL.revokeObjectURL(url)
        resolve(canvas.toDataURL('image/jpeg', 0.85))
      } catch (e) {
        URL.revokeObjectURL(url)
        reject(e)
      }
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('图片加载失败，请换一张试试'))
    }
    img.src = url
  })
}

/** UTF-8 字节数（增补字符按代理对合并计 4 字节，与 Java getBytes(StandardCharsets.UTF_8) 口径一致） */
function utf8Bytes(s) {
  let n = 0
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c < 0x80) n += 1
    else if (c < 0x800) n += 2
    else if (c >= 0xD800 && c <= 0xDBFF) { n += 4; i++ }
    else n += 3
  }
  return n
}

/** 头像长度前端预校验（与后端 PUT 上限一致，超限免一次注定 400 的请求）：
 * 后端按 UTF-8 字节判（UserController.AVATAR_MAX_BYTES=60000，trimmed.getBytes(UTF_8)），
 * 前端同按字节口径，防多字节内容（如 60000 汉字=180000 字节）字符数放行、后端 400 的口径漏判 */
export function isAvatarTooLarge(dataURI) {
  return typeof dataURI === 'string' && utf8Bytes(dataURI) > 60000
}
