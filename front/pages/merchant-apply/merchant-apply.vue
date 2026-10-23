<template>
  <view class="page-container">
    <!-- 首屏加载：不渲染表单，避免未登录时闪出可提交界面 -->
    <view v-if="loading && !loginRedirected" class="card">
      <text class="loading-text">加载中…</text>
    </view>

    <!-- 已跳登录页：登录页返回本页时给静态提示与再次登录入口，不无限停在「加载中」 -->
    <view v-else-if="loginRedirected" class="card">
      <view class="card-head"><text class="card-title">请先登录</text></view>
      <text class="card-desc">登录后即可申请成为商家</text>
      <button class="btn-main" @click="retryLogin"><text>去登录</text></button>
    </view>

    <!-- 拉取申请状态失败：给重试入口，不静默 -->
    <view v-else-if="loadError" class="card">
      <view class="card-head"><text class="card-title">加载失败</text></view>
      <text class="card-desc">请检查网络后重新加载</text>
      <button class="btn-main" @click="loadMine()"><text>重新加载</text></button>
    </view>

    <template v-else>
      <!-- ==================== 1. 申请状态卡（pending / rejected / approved） ==================== -->
      <view v-if="applyStatus" class="card">
        <view class="status-head">
          <text class="status-badge" :class="meta.badgeClass">{{ meta.text }}</text>
          <text class="status-title">{{ apply.merchantName || '商家入驻申请' }}</text>
        </view>
        <text class="status-desc">{{ meta.desc }}</text>
        <view class="summary-row">
          <text class="summary-label">商家名称</text>
          <text class="summary-value">{{ apply.merchantName || '-' }}</text>
        </view>
        <view class="summary-row">
          <text class="summary-label">提交时间</text>
          <text class="summary-value">{{ apply.createdAt || '-' }}</text>
        </view>
        <view v-if="apply.status === 'rejected'" class="summary-row">
          <text class="summary-label">驳回理由</text>
          <text class="summary-value summary-remark">{{ apply.auditRemark || '未填写驳回理由' }}</text>
        </view>
        <view v-if="apply.auditedAt" class="summary-row">
          <text class="summary-label">审核时间</text>
          <text class="summary-value">{{ apply.auditedAt }}</text>
        </view>
        <!-- 已提交态证照列表（pending/approved/rejected 通用）：数据来自 /mine 的 licenses 字符串 -->
        <view v-if="applyLicenses.length" class="license-preview">
          <text class="summary-label">证照</text>
          <view class="license-preview-list">
            <view v-for="(item, index) in applyLicenses" :key="index" class="license-preview-item">
              <image class="license-preview-img" :src="item.fullUrl" mode="aspectFill" />
              <text class="license-preview-name">{{ item.typeLabel }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- ==================== 2. 入驻申请表单（pending/approved 不展示；rejected 可重新提交） ==================== -->
      <view v-if="showForm" class="card">
        <view class="card-head"><text class="card-title">商家信息</text></view>
        <text class="card-desc">带 * 为必填项，提交后由平台审核</text>

        <view class="field">
          <text class="field-label">商家名称<text class="required">*</text></text>
          <view class="input-capsule">
            <input
              class="capsule-input"
              v-model="form.merchantName"
              maxlength="100"
              placeholder="请输入商家名称"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
        </view>

        <view class="field">
          <text class="field-label">联系人</text>
          <view class="input-capsule">
            <input
              class="capsule-input"
              v-model="form.contactName"
              maxlength="50"
              placeholder="请输入联系人姓名"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
        </view>

        <view class="field">
          <text class="field-label">法人姓名</text>
          <view class="input-capsule">
            <input
              class="capsule-input"
              v-model="form.legalPerson"
              maxlength="50"
              placeholder="请输入法人姓名"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
        </view>

        <view class="field">
          <text class="field-label">经营地址</text>
          <view class="input-capsule">
            <input
              class="capsule-input"
              v-model="form.address"
              maxlength="255"
              placeholder="请输入经营地址"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
        </view>

        <view class="field">
          <text class="field-label">结算账户</text>
          <view class="input-capsule">
            <input
              class="capsule-input"
              v-model="form.settlementAccount"
              maxlength="64"
              placeholder="请输入结算账户（选填）"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
        </view>

        <view class="field">
          <text class="field-label">证照<text class="required">*</text></text>
          <text class="license-tip-line">至少 1 张营业执照，最多 {{ maxLicenses }} 张；支持 jpg/png/webp，单张不超过 5MB</text>
          <view v-for="(item, index) in licenseItems" :key="index" class="license-row">
            <view class="license-thumb" @click="chooseLicenseImage(index)">
              <image v-if="item.url" class="license-thumb-img" :src="resolveImageUrl(item.url)" mode="aspectFill" />
              <view v-else class="license-thumb-placeholder">
                <text class="license-plus">＋</text>
                <text class="license-thumb-tip">上传</text>
              </view>
              <view v-if="uploading && uploadingIndex === index" class="license-mask">
                <text class="license-mask-text">上传中…</text>
              </view>
            </view>
            <view class="license-row-info">
              <picker
                class="license-type-picker"
                mode="selector"
                :range="licenseTypeLabels"
                :value="licenseTypeIndex(item.type)"
                @change="onLicenseTypeChange(index, $event)"
              >
                <view class="license-type">
                  <text class="license-type-text">{{ licenseTypeLabel(item.type) }}</text>
                  <text class="license-type-arrow">▾</text>
                </view>
              </picker>
              <view class="license-row-actions">
                <text class="license-action" @click.stop="chooseLicenseImage(index)">{{ item.url ? '重新上传' : '上传图片' }}</text>
                <text class="license-action license-action-del" @click.stop="removeLicense(index)">删除</text>
              </view>
            </view>
          </view>
          <view v-if="canAddLicense" class="license-add" @click="addLicense">
            <text>＋ 添加证照</text>
          </view>
        </view>

        <button class="btn-main" :class="{ 'btn-main-disabled': submitting || uploading }" :disabled="submitting || uploading" @click="submitApply"><text>{{ submitting ? '提交中…' : '提交申请' }}</text></button>
        <text class="form-tip">提交后平台将尽快审核，审核结果可在本页查看</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 申请成为商家页（F-106 片 D）：C 端自助入驻入口。
// 状态机以 GET /api/merchant-applies/mine 为唯一真源：
// - pending → 只展示「审核中」卡片 + 申请摘要，不渲染表单（后端同时封死重复提交）；
// - rejected → 展示驳回理由，表单可重新填写提交（成功后再拉 /mine 切到 pending）；
// - approved → 展示「已开通」并提示可去商家端；
// - 无申请 → 直接展示表单。
// 未登录/登录态过期：统一提示后带 redirect 跳登录页，登录成功回跳本页。
// F-107：营业执照单图升级为多证照（1~6 张，必含一张营业执照）；旧字段 businessLicense 仍随提交。
import { mapState } from 'vuex'
import { getMyMerchantApply, submitMerchantApply, uploadMerchantLicense } from '@/api/index.js'
import { BASE_URL } from '@/api/config.js'

// 证照类型：中文名只在本常量维护，提交时只发英文 key（后端只认这 4 个值）
const LICENSE_TYPES = {
  business_license: '营业执照',
  food_business_license: '食品经营许可证',
  food_production_license: '食品生产许可证',
  other: '其他'
}
const LICENSE_TYPE_KEYS = Object.keys(LICENSE_TYPES)
const LICENSE_TYPE_LABELS = LICENSE_TYPE_KEYS.map(key => LICENSE_TYPES[key])
const MAX_LICENSES = 6
// 只认自有 key：后端旧数据可能出现任意 type 字符串，避免命中 Object 原型属性后渲染出函数
function licenseTypeLabelOf(type) {
  return Object.prototype.hasOwnProperty.call(LICENSE_TYPES, type) ? LICENSE_TYPES[type] : LICENSE_TYPES.other
}

// 状态文案映射：pending 描述直接采用后端既有文案「已有待审核的入驻申请，请等待审核结果」，
// 不另造同义句；rejected/approved 为展示态文案
const STATUS_META = {
  pending: {
    text: '审核中',
    desc: '已有待审核的入驻申请，请等待审核结果',
    badgeClass: 'status-pending'
  },
  rejected: {
    text: '已驳回',
    desc: '申请未通过，请按驳回理由修改后重新提交',
    badgeClass: 'status-rejected'
  },
  approved: {
    text: '已开通',
    desc: '您的商家已开通，可前往商家端登录使用',
    badgeClass: 'status-approved'
  }
}

export default {
  data() {
    return {
      loading: true, // 首屏拉取 /mine 中
      loadError: false, // 拉取失败（非 401），模板给重试入口
      loginRedirected: false, // 已跳登录页（防重复 navigateTo、防表单闪现）
      apply: null, // /mine 返回的申请摘要；无申请为 null
      submitting: false, // 提交中防重
      uploading: false, // 证照上传中防重
      uploadingIndex: -1, // 正在上传的证照行下标（-1=无），仅用于该行遮罩
      maxLicenses: MAX_LICENSES, // 证照张数上限（模板提示与添加按钮共用）
      licenseTypeLabels: LICENSE_TYPE_LABELS, // 类型下拉的展示文案（顺序与 LICENSE_TYPE_KEYS 一致）
      // 证照行：type 存英文 key，url 存上传返回的站内 path；默认先给一行营业执照
      licenseItems: [{ type: 'business_license', url: '' }],
      form: {
        // 键名与 MerchantApplyCreateDTO 逐字一致
        merchantName: '',
        contactName: '',
        legalPerson: '',
        address: '',
        settlementAccount: ''
      }
    }
  },
  computed: {
    ...mapState(['user']),
    hasToken() { return !!(this.user && this.user.token) },
    applyStatus() { return (this.apply && this.apply.status) ? this.apply.status : '' },
    // 未知状态也放行表单：不让用户卡在无法操作的页面（重复提交由后端 400 文案把关）
    showForm() { return this.applyStatus !== 'pending' && this.applyStatus !== 'approved' },
    meta() {
      return STATUS_META[this.applyStatus] || {
        text: '状态未知',
        desc: '暂无法识别申请状态，请重新加载或联系平台客服',
        badgeClass: 'status-pending'
      }
    },
    // 已提交态证照展示：/mine 的 licenses 是 JSON 字符串；坏数据（非 JSON/非数组）一律当空数组，页面不崩
    applyLicenses() {
      const raw = this.apply ? this.apply.licenses : null
      if (!raw) return []
      let list = null
      try {
        list = typeof raw === 'string' ? JSON.parse(raw) : raw
      } catch (e) {
        return []
      }
      if (!Array.isArray(list)) return []
      const items = []
      for (let i = 0; i < list.length && items.length < MAX_LICENSES; i++) {
        const it = list[i]
        if (!it || typeof it !== 'object' || typeof it.url !== 'string' || !it.url) continue
        items.push({
          typeLabel: licenseTypeLabelOf(it.type),
          fullUrl: this.resolveImageUrl(it.url)
        })
      }
      return items
    },
    // 证照上限：满 6 张后隐藏「添加证照」入口
    canAddLicense() {
      return this.licenseItems.length < MAX_LICENSES
    }
  },
  onLoad() {
    // 登录态守卫：本页 GET /mine 与 POST 申请都需 token；无 token 直接带 redirect 去登录
    if (!this.hasToken) {
      this.redirectLogin()
      return
    }
    this.loadMine()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管（照账号安全页同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与 onReady 的注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    showToast(title, duration) {
      uni.showToast({ title, icon: 'none', duration: duration || 2000 })
    },
    // 未登录统一出口：提示 + 带 redirect 跳独立登录页（写法照我的页既有登录入口），登录成功回跳本页
    redirectLogin() {
      if (this.loginRedirected) return
      this.loginRedirected = true
      this.showToast('请先登录', 1500)
      uni.navigateTo({
        url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/merchant-apply/merchant-apply')
      })
    },
    // 登录守卫卡上的「去登录」：复位标记后重走 redirectLogin（用户从登录页返回但未登录时仍可再进）
    retryLogin() {
      this.loginRedirected = false
      this.redirectLogin()
    },
    // 拉取我的最新一条申请；silent=true 用于提交成功后的静默刷新（不再闪首屏 loading）
    async loadMine(silent) {
      if (!silent) this.loading = true
      this.loadError = false
      try {
        const res = await getMyMerchantApply()
        const data = res ? res.data : null
        // 契约口径：从未申请过时 data===null；后端现实现返回七键全 null 的对象，两种形态都按「无申请」处理
        if (data === null || data === undefined || (data.id == null && data.status == null)) {
          this.apply = null
          return
        }
        this.apply = data
        // 驳回后重提：把上次的商家名称回填，其余字段不在 /mine 白名单内，无法回填
        if (data.status === 'rejected') {
          this.form.merchantName = data.merchantName || this.form.merchantName
        }
      } catch (e) {
        if (e && e.code === 401) {
          this.redirectLogin()
          return
        }
        if (!silent) this.loadError = true
        this.showToast((e && e.message) || '网络异常')
      } finally {
        if (!silent) this.loading = false
      }
    },
    // 站内相对路径补 BASE_URL 后展示缩略图（历史数据若已是完整 http(s) 地址则原样用）
    resolveImageUrl(p) {
      if (!p) return ''
      return /^https?:\/\//.test(p) ? p : BASE_URL + p
    },
    // ==================== 证照多图（F-107） ====================
    licenseTypeLabel(type) {
      return licenseTypeLabelOf(type)
    },
    licenseTypeIndex(type) {
      const idx = LICENSE_TYPE_KEYS.indexOf(type)
      return idx < 0 ? LICENSE_TYPE_KEYS.length - 1 : idx
    },
    // 类型下拉切换：只在前端把中文名换成英文 key，不改动已上传的 url
    onLicenseTypeChange(index, e) {
      const item = this.licenseItems[index]
      const key = LICENSE_TYPE_KEYS[Number(e && e.detail ? e.detail.value : -1)]
      if (!item || !key) return
      item.type = key
    },
    addLicense() {
      if (this.uploading) return
      if (this.licenseItems.length >= this.maxLicenses) {
        this.showToast('最多添加 ' + this.maxLicenses + ' 张证照', 1500)
        return
      }
      this.licenseItems.push({ type: 'other', url: '' })
    },
    // 选择某一行证照图：成功后上传拿 path 存入该行（入库用 path，展示时补 BASE_URL）
    chooseLicenseImage(index) {
      if (this.uploading || this.submitting) return
      if (!this.licenseItems[index]) return
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: (res) => {
          const filePath = (res && res.tempFilePaths && res.tempFilePaths[0]) || ''
          if (filePath) this.doUploadLicense(filePath, index)
        }
      })
    },
    async doUploadLicense(filePath, index) {
      const item = this.licenseItems[index]
      if (!item || this.uploading) return
      this.uploading = true
      this.uploadingIndex = index
      try {
        const res = await uploadMerchantLicense(filePath)
        const path = res && res.data && res.data.path
        if (!path) {
          this.showToast('上传失败，请重试')
          return
        }
        item.url = path
        this.showToast('证照已上传', 1500)
      } catch (e) {
        if (e && e.code === 401) {
          this.redirectLogin()
          return
        }
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.uploading = false
        this.uploadingIndex = -1
      }
    },
    removeLicense(index) {
      if (this.uploading) return
      this.licenseItems.splice(index, 1)
    },
    // 提交申请：POST /api/merchant-applies；成功 toast 后端 message 后重新拉 /mine 切到「审核中」；
    // 400（已是商家 / 已有待审核申请 / 参数校验）原样展示后端 message，不改写
    async submitApply() {
      if (this.submitting || this.uploading) return
      const merchantName = (this.form.merchantName || '').trim()
      if (!merchantName) {
        this.showToast('请填写商家名称', 1500)
        return
      }
      // 证照：未上传图片的空行不提交；至少一张营业执照（前端先行拦截，文案不复用后端）
      const licenses = this.licenseItems
        .filter(item => item && item.url)
        .map(item => ({ type: item.type, url: item.url }))
      if (!licenses.length) {
        this.showToast('请先上传证照图片', 1500)
        return
      }
      const businessLicense = licenses.find(item => item.type === 'business_license')
      if (!businessLicense) {
        this.showToast('证照中需包含营业执照，请补充后再提交', 1800)
        return
      }
      this.submitting = true
      try {
        const res = await submitMerchantApply({
          merchantName,
          contactName: (this.form.contactName || '').trim(),
          legalPerson: (this.form.legalPerson || '').trim(),
          address: (this.form.address || '').trim(),
          settlementAccount: (this.form.settlementAccount || '').trim(),
          // 旧字段照旧提交：第一条营业执照的 path（兼容后端旧校验与旧读路径）
          businessLicense: businessLicense.url,
          // 新字段：JSON 数组字符串，后端按文本原样落库
          licenses: JSON.stringify(licenses)
        })
        this.showToast((res && res.message) || '申请已提交，请等待审核结果')
        await this.loadMine(true)
      } catch (e) {
        if (e && e.code === 401) {
          this.redirectLogin()
          return
        }
        this.showToast((e && e.message) || '网络异常', 2500)
      } finally {
        this.submitting = false
      }
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 手动驱动 body 滚动（照我的页/账号安全页同款方案）；uni 弹层（含证照类型 picker）打开时命中弹层节点直接放行
    onPageWheel(e) {
      const t = e.target
      const el = t && t.nodeType === 1 ? t : (t && t.parentElement)
      if (el && typeof el.closest === 'function'
        && el.closest('.uni-actionsheet, .uni-picker, .uni-picker-container, .uni-picker-content, .uni-modal, .uni-toast, .uni-sample-toast')) {
        return
      }
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 多多买菜工具风 — 申请成为商家页（白底 + 主红 #E02020） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 白卡通用（照账号安全页） ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.card-head { margin-bottom: 8rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.card-desc {
  display: block;
  margin-bottom: 24rpx;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}
.loading-text {
  display: block; padding: 40rpx 0;
  font-size: 26rpx; color: #999999; text-align: center;
}

/* ==================== 1. 申请状态卡 ==================== */
.status-head { display: flex; align-items: center; gap: 16rpx; margin-bottom: 12rpx; }
.status-badge {
  flex-shrink: 0;
  font-size: 22rpx; font-weight: 600;
  padding: 4rpx 16rpx; border-radius: 999rpx;
}
.status-pending { color: #C77C00; background-color: #FFF4E0; }
.status-rejected { color: #E02020; background-color: #FFECE8; }
.status-approved { color: #1F9D55; background-color: #E8F7EE; }
.status-title {
  flex: 1; min-width: 0;
  font-size: 30rpx; font-weight: 700; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.status-desc { display: block; margin-bottom: 16rpx; font-size: 22rpx; color: #999999; line-height: 1.5; }
.summary-row { display: flex; align-items: flex-start; padding: 10rpx 0; font-size: 26rpx; line-height: 1.5; }
.summary-label { flex-shrink: 0; width: 140rpx; color: #999999; }
.summary-value { flex: 1; min-width: 0; color: #333333; word-break: break-all; }
.summary-remark { color: #E02020; }

/* ==================== 1.1 已提交态证照预览 ==================== */
.license-preview { display: flex; align-items: flex-start; padding: 10rpx 0; font-size: 26rpx; line-height: 1.5; }
.license-preview-list { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; gap: 16rpx; }
.license-preview-item { width: 132rpx; }
.license-preview-img {
  width: 132rpx; height: 132rpx; display: block;
  border-radius: 12rpx; background-color: #F5F5F5;
}
.license-preview-name {
  display: block; margin-top: 8rpx;
  font-size: 20rpx; color: #666666; text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ==================== 2. 表单字段 ==================== */
.field { margin-bottom: 24rpx; }
.field-label { display: block; margin-bottom: 12rpx; font-size: 26rpx; font-weight: 600; color: #333333; }
.required { color: #E02020; margin-left: 4rpx; }
.input-capsule {
  display: flex; align-items: center;
  height: 92rpx;
  background-color: #F5F5F5; border-radius: 16rpx;
  padding: 0 28rpx; box-sizing: border-box;
}
.capsule-input {
  flex: 1; min-width: 0;
  height: 92rpx; line-height: 92rpx;
  font-size: 28rpx; color: #333333;
}

/* ==================== 2.1 证照（多图 + 类型下拉） ==================== */
.license-tip-line { display: block; margin-bottom: 16rpx; font-size: 22rpx; color: #999999; line-height: 1.5; }
.license-row {
  display: flex; align-items: center; gap: 20rpx;
  margin-bottom: 16rpx; padding: 16rpx;
  background-color: #F9F9F9; border-radius: 16rpx;
  box-sizing: border-box;
}
.license-thumb {
  position: relative; flex-shrink: 0;
  width: 120rpx; height: 120rpx;
  border-radius: 12rpx; overflow: hidden;
  background-color: #F0F0F0;
}
.license-thumb-img { width: 120rpx; height: 120rpx; display: block; }
.license-thumb-placeholder {
  width: 120rpx; height: 120rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4rpx;
}
.license-plus { font-size: 36rpx; color: #BBBBBB; line-height: 1; }
.license-thumb-tip { font-size: 20rpx; color: #999999; }
.license-mask {
  position: absolute; left: 0; top: 0; right: 0; bottom: 0;
  display: flex; align-items: center; justify-content: center;
  background-color: rgba(0, 0, 0, 0.45);
}
.license-mask-text { font-size: 22rpx; color: #FFFFFF; }
.license-row-info { flex: 1; min-width: 0; }
.license-type-picker { display: block; }
.license-type {
  display: flex; align-items: center; justify-content: space-between;
  height: 64rpx; padding: 0 20rpx;
  background-color: #FFFFFF; border-radius: 12rpx;
  box-sizing: border-box;
}
.license-type-text {
  flex: 1; min-width: 0;
  font-size: 26rpx; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.license-type-arrow { flex-shrink: 0; margin-left: 12rpx; font-size: 20rpx; color: #999999; }
.license-row-actions { display: flex; align-items: center; gap: 28rpx; margin-top: 14rpx; }
.license-action { font-size: 24rpx; color: #E02020; }
.license-action-del { color: #999999; }
.license-add {
  height: 80rpx; line-height: 80rpx;
  border: 2rpx dashed #DDDDDD; border-radius: 16rpx;
  text-align: center; font-size: 24rpx; color: #999999;
}

/* ==================== 提交按钮（显式覆盖 uni-button 默认圆角/行高/边框） ==================== */
.btn-main {
  width: 100%; height: 88rpx; line-height: 88rpx;
  margin-top: 8rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 32rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
.btn-main::after { border: none; }
.btn-main.btn-main-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }
.form-tip {
  display: block; margin-top: 16rpx;
  font-size: 22rpx; color: #999999; text-align: center; line-height: 1.5;
}

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
