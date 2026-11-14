// ==================== C 端大图预览层（PC 滚轮缩放，detail/review 评价图共用） ====================
// 背景：uni.previewImage（uni-h5 内置层）PC 端滚轮不能缩放（用户实测反馈），改为自建 DOM 层：
// 全屏 fixed 挂 document.body，不依赖 Vue 组件体系（非 Vue 组件内也可调用）。
// 能力：滚轮缩放（0.5~8，以鼠标位置为锚点）、按住拖拽平移、左右键/箭头切图、双击复位/放大、
// 右上 × / 点击遮罩空白 / Esc 三路关闭。切图复位缩放；单例（重复调用先关旧层再开新层）。
// 样式全部 JS 注入 style 标签，类名 imgp- 前缀防冲突；不引第三方库。

// 层级：必须盖过 uni-h5 一切内置层（uni-page-head 998 / uni-preview-image 999）
const OVERLAY_Z_INDEX = 3000
// 缩放边界：0.5（缩小一半）~ 8（放大八倍）
const SCALE_MIN = 0.5
const SCALE_MAX = 8
// 滚轮缩放步进系数：deltaY 归一后按指数平滑换算（exp 曲线，滚轮一格约 ±11%）
const WHEEL_STEP = 0.0015

// 单例句柄：同一时刻最多一个预览层，防多层叠加
let active = null
// style 标签只注入一次
let styleInjected = false

const CSS = `
.imgp-overlay{position:fixed;left:0;top:0;right:0;bottom:0;z-index:${OVERLAY_Z_INDEX};background:rgba(0,0,0,.85);display:flex;align-items:center;justify-content:center;user-select:none;-webkit-user-select:none;touch-action:none}
.imgp-img{max-width:90vw;max-height:90vh;object-fit:contain;transition:opacity .15s;-webkit-user-drag:none;cursor:grab;will-change:transform}
.imgp-img--grabbing{cursor:grabbing}
.imgp-close{position:absolute;top:12px;right:20px;width:44px;height:44px;line-height:44px;text-align:center;font-size:36px;color:#fff;cursor:pointer;z-index:1}
.imgp-arrow{position:absolute;top:50%;margin-top:-32px;width:44px;height:64px;line-height:60px;text-align:center;font-size:44px;color:rgba(255,255,255,.75);cursor:pointer;z-index:1}
.imgp-arrow:hover{color:#fff}
.imgp-arrow--left{left:16px}
.imgp-arrow--right{right:16px}
.imgp-footer{position:absolute;left:0;right:0;bottom:18px;text-align:center;color:rgba(255,255,255,.8);font-size:13px;line-height:1.8;z-index:1;pointer-events:none}
`

/**
 * 打开大图预览层（自建 DOM 层，替代 uni.previewImage 的 PC 滚轮缩放场景）
 * 单例：已开层先关闭再开新层；非浏览器环境（非 H5）直接返回不动作
 * @param {object} opts
 * @param {string[]} opts.urls - 图片 URL 列表（绝对地址，非空项）
 * @param {string|number} [opts.current] - 初始定位：URL 字符串（按 urls.indexOf 匹配）或序号，缺省/未命中取第一张
 * @returns {{ close: () => void }|null} 预览层句柄（close 手动关闭）；环境不支持或 urls 为空返回 null
 */
export function openImgPreview({ urls, current } = {}) {
  // 环境防御：依赖 DOM（uni-h5 H5 端专属），非浏览器环境直接返回
  if (typeof window === 'undefined' || typeof document === 'undefined') return null
  // urls 归一：剔除非字符串/空项；空列表不拉层
  const list = (Array.isArray(urls) ? urls : []).filter(u => typeof u === 'string' && u)
  if (!list.length) return null
  // 单例：先关旧层再开新层（防叠加）
  if (active) active.close()

  // 初始定位：数字按序号、字符串按 URL 匹配，均未命中取第一张
  let idx = 0
  if (typeof current === 'number' && Number.isFinite(current)) {
    idx = Math.min(Math.max(Math.round(current), 0), list.length - 1)
  } else if (typeof current === 'string') {
    const i = list.indexOf(current)
    if (i >= 0) idx = i
  }

  ensureStyle()

  // ==================== DOM 构建 ====================
  const overlay = document.createElement('div')
  overlay.className = 'imgp-overlay'
  const img = document.createElement('img')
  img.className = 'imgp-img'
  img.alt = '预览图片'
  img.draggable = false // 关闭原生拖图，让位自建拖拽平移
  const closeBtn = document.createElement('div')
  closeBtn.className = 'imgp-close'
  closeBtn.textContent = '×'
  const leftBtn = document.createElement('div')
  leftBtn.className = 'imgp-arrow imgp-arrow--left'
  leftBtn.textContent = '‹'
  const rightBtn = document.createElement('div')
  rightBtn.className = 'imgp-arrow imgp-arrow--right'
  rightBtn.textContent = '›'
  const footer = document.createElement('div')
  footer.className = 'imgp-footer'
  overlay.appendChild(img)
  overlay.appendChild(closeBtn)
  overlay.appendChild(footer)
  if (list.length > 1) {
    overlay.appendChild(leftBtn)
    overlay.appendChild(rightBtn)
  }

  // ==================== 视图状态与变换 ====================
  // scale：当前缩放倍数；tx/ty：相对 flex 居中位置的平移（未缩放像素，拖拽 1:1 跟手）
  // transform 顺序 translate→scale（scale 先作用于自身中心，再整体平移）
  let scale = 1
  let tx = 0
  let ty = 0

  function applyTransform(anim) {
    // 内联 transition 需带上 opacity（inline 会整体覆盖 CSS 的 .imgp-img 过渡声明，
    // 只写 transform 会把切图淡入的 opacity 过渡一并干掉）
    img.style.transition = anim ? 'transform .2s ease-out, opacity .15s' : 'opacity .15s'
    img.style.transform = 'translate(' + tx + 'px, ' + ty + 'px) scale(' + scale + ')'
  }

  function resetView(anim) {
    scale = 1
    tx = 0
    ty = 0
    applyTransform(anim)
  }

  /**
   * 以 overlay 坐标 (cx, cy) 为锚点缩放到 next：
   * 锚点相对"视觉中心"的向量按缩放比换算后反推新平移，保证鼠标所指的图上点不移动
   *（getBoundingClientRect 含当前 transform，直接拿视觉中心，免手算 scale 链）
   */
  function zoomAt(cx, cy, next) {
    next = Math.min(SCALE_MAX, Math.max(SCALE_MIN, next))
    if (next === scale) return // 已在边界且无变化：平移也不动
    const oRect = overlay.getBoundingClientRect()
    const iRect = img.getBoundingClientRect()
    const vcx = iRect.left - oRect.left + iRect.width / 2
    const vcy = iRect.top - oRect.top + iRect.height / 2
    const vx = cx - vcx
    const vy = cy - vcy
    const k = next / scale
    tx += (cx - vx * k) - vcx
    ty += (cy - vy * k) - vcy
    scale = next
    applyTransform(false)
  }

  // ==================== 切图与计数 ====================
  function updateFooter() {
    const pos = list.length > 1 ? (idx + 1) + ' / ' + list.length + '　' : ''
    footer.textContent = pos + '滚轮缩放 · 拖动平移 · 双击复位 · Esc 关闭'
  }

  // 加载中先隐去旧图防串图；失败也露出（破图图标可见，不算静默黑屏）
  function show(i) {
    idx = (i + list.length) % list.length
    img.style.opacity = '0'
    img.src = list[idx]
    resetView(false) // 切图复位缩放/平移
    updateFooter()
  }

  function step(dir) { show(idx + dir) }

  function onLoad() { img.style.opacity = '1' }
  function onError() { img.style.opacity = '1' }

  // ==================== 交互事件 ====================
  // 滚轮缩放：deltaY 归一（deltaMode 1=行×16px、2=页×100px），exp 平滑，锚点=鼠标位置
  function onWheel(e) {
    e.preventDefault() // 阻止透传滚动页面
    e.stopPropagation()
    let dy = e.deltaY
    if (e.deltaMode === 1) dy *= 16
    else if (e.deltaMode === 2) dy *= 100
    const oRect = overlay.getBoundingClientRect()
    zoomAt(e.clientX - oRect.left, e.clientY - oRect.top, scale * Math.exp(-dy * WHEEL_STEP))
  }

  // 拖拽平移：pointer 事件（鼠标/触摸/笔通吃），setPointerCapture 移出图片仍跟手
  let dragging = false
  let lastX = 0
  let lastY = 0

  function onPointerDown(e) {
    dragging = true
    lastX = e.clientX
    lastY = e.clientY
    img.classList.add('imgp-img--grabbing')
    try { img.setPointerCapture(e.pointerId) } catch (err) { /* 老 IE/异常环境降级为不捕获 */ }
    e.preventDefault() // 防触发原生图片拖拽/文字选择
  }

  function onPointerMove(e) {
    if (!dragging) return
    tx += e.clientX - lastX
    ty += e.clientY - lastY
    lastX = e.clientX
    lastY = e.clientY
    applyTransform(false)
  }

  function onPointerUp(e) {
    if (!dragging) return
    dragging = false
    img.classList.remove('imgp-img--grabbing')
    try { img.releasePointerCapture(e.pointerId) } catch (err) { /* 同上 */ }
  }

  // 双击：已放大 → 复位；1x → 以双击点为锚放大 2.5x
  function onDblClick(e) {
    if (scale > 1.01) {
      resetView(true)
      return
    }
    const oRect = overlay.getBoundingClientRect()
    zoomAt(e.clientX - oRect.left, e.clientY - oRect.top, 2.5)
  }

  // 键盘：Esc 关闭，←/→ 切图（单图时不切，防无效复位）
  function onKeydown(e) {
    if (e.key === 'Escape') {
      e.preventDefault()
      close()
    } else if (e.key === 'ArrowLeft' && list.length > 1) {
      e.preventDefault()
      step(-1)
    } else if (e.key === 'ArrowRight' && list.length > 1) {
      e.preventDefault()
      step(1)
    }
  }

  // 关闭：遮罩空白（仅 overlay 自身）或 × 按钮
  function onOverlayClick(e) {
    if (e.target === overlay) close()
  }
  function onCloseBtnClick(e) {
    e.stopPropagation()
    close()
  }

  // ==================== 生命周期 ====================
  // 监听登记表：关闭时逐条解绑，防泄漏
  const bound = []

  function bind(el, type, fn, opts) {
    el.addEventListener(type, fn, opts)
    bound.push([el, type, fn, opts])
  }

  function unbindAll() {
    bound.forEach(([el, type, fn, opts]) => el.removeEventListener(type, fn, opts))
    bound.length = 0
  }

  const api = { close }

  function close() {
    unbindAll()
    if (overlay.parentNode) overlay.parentNode.removeChild(overlay)
    if (active === api) active = null
  }

  // 路由跳转兜底：uni-h5 H5 路由（hash/history 两种模式）切页时自动关层，防残留
  bind(window, 'popstate', close)
  bind(window, 'hashchange', close)

  bind(overlay, 'wheel', onWheel, { passive: false }) // 需 preventDefault，必须非 passive
  bind(img, 'pointerdown', onPointerDown)
  bind(img, 'pointermove', onPointerMove)
  bind(img, 'pointerup', onPointerUp)
  bind(img, 'pointercancel', onPointerUp)
  bind(img, 'dblclick', onDblClick)
  bind(img, 'load', onLoad)
  bind(img, 'error', onError)
  bind(overlay, 'click', onOverlayClick)
  bind(closeBtn, 'click', onCloseBtnClick)
  if (list.length > 1) {
    bind(leftBtn, 'click', () => step(-1))
    bind(rightBtn, 'click', () => step(1))
  }
  bind(document, 'keydown', onKeydown)

  document.body.appendChild(overlay)
  show(idx)
  active = api
  return api
}

// style 标签注入（首次调用时一次，后续复用）
function ensureStyle() {
  if (styleInjected) return
  const tag = document.createElement('style')
  tag.textContent = CSS
  ;(document.head || document.documentElement).appendChild(tag)
  styleInjected = true
}
