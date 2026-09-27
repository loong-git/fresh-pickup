<template>
  <view class="page-container">
    <!-- 主滚动区（scroll-view 底部预留 300rpx，给固定购物车栏 + 平台优惠条让位） -->
    <scroll-view
      class="main-scroll"
      scroll-y
      :scroll-top="mainScrollTop"
      :scroll-with-animation="true"
      @scroll="onMainScroll"
    >
      <view class="scroll-inner">
        <!-- ==================== 吸顶层：仅搜索框（原生 sticky 吸附于滚动区顶部；未滚动红底，分类 tab 吸顶时刻随 catTabStuck 切白底） ==================== -->
        <view class="sticky-header" :class="{ stuck: catTabStuck }">
          <view class="red-header">
            <!-- 一体式搜索白条：🔍 + 真实 input + 竖分隔线 + "搜索"红字（点击区域 padding 0 28rpx，不再独立胶囊） -->
            <!-- T-M3-02 搜索真实化 + 独立搜索页接管：@input 300ms 防抖即搜（不记历史），@confirm 回车与"搜索"点击同走 onSearch（跳搜索页，携已输入词） -->
            <!-- 搜索入口：整条点击跳独立搜索页（搜索交互全部在搜索页内完成） -->
            <view class="search-box" @click="onSearch">
              <text class="search-icon">🔍</text>
              <text class="search-input">苹果醋</text>
              <view class="search-divider"></view>
              <view class="search-go"><text class="search-go-text">搜索</text></view>
            </view>
          </view>
        </view>


        <!-- ==================== 2+3. 红色横幅区：截单提醒红条 + 店铺行 + 优惠券三卡 ==================== -->
        <view class="red-zone">
          <!-- T-M3-04 截单前 1 小时（22:00-23:00）顶部红条：倒计时 tick 内维护显隐 -->
          <view class="cutoff-bar" v-if="showCutoffSoon">
            <text class="cutoff-bar-text">⏰ 距离今日截单不足 1 小时，请尽快下单</text>
          </view>
          <!-- 店铺行：整行可点跳自提点，"订单"/"我的"按钮单独拦截跳订单页/个人中心页 -->
          <view class="store-row" @click="goToPickup">
            <text class="store-icon">🏪</text>
            <text class="store-name">{{ pickupPoint.name }}</text>
            <text class="store-service">{{ pickupPoint.service }}</text>
            <view class="store-order" @click.stop="goToOrder"><text>订单</text></view>
            <view class="store-order" @click.stop="goToMine"><text>我的</text></view>
          </view>
          <!-- 优惠券三卡 -->
          <view class="coupon-banner">
            <view class="coupon-cards">
              <view class="coupon-card" v-for="(c, i) in coupons" :key="i" @click="onCouponTap(c)">
                <text class="coupon-main">{{ c.main }}</text>
                <view class="coupon-status">
                  <text v-if="!c.locked" class="coupon-use-btn">{{ c.status }}</text>
                  <text v-else class="coupon-lock">🔒 {{ c.status }}</text>
                </view>
              </view>
            </view>
            <text class="coupon-tip">全场用券超划算，下单次日即可领取下一张券</text>
          </view>
        </view>

        <!-- ==================== 4. 分类 tab（券区之后、金刚区之前；自身 sticky 吸附于搜索框正下方，始终白底；搜索模式下隐藏让位结果列表） ==================== -->
        <view class="cat-tab-bar">
          <view class="cat-tabs">
            <view class="cat-tab" :class="{ active: currentCategory === 'all' }" @click="switchCategory('all')"><text>推荐</text></view>
            <view class="cat-tab" :class="{ active: currentCategory === 'meat' }" @click="switchCategory('meat')"><text>肉蛋水产</text></view>
            <view class="cat-tab" :class="{ active: currentCategory === 'vegetable' }" @click="switchCategory('vegetable')"><text>新鲜蔬菜</text></view>
            <!-- 速食：code 对齐字典 dish_category 的 code:fastfood（mapCategories/pickCategory 同 key，点击真实过滤） -->
            <view class="cat-tab" :class="{ active: currentCategory === 'fastfood' }" @click="switchCategory('fastfood')"><text>速食</text></view>
          </view>
          <view class="cat-all" @click="openCategoryPopup"><text>≡ 全部</text></view>
        </view>

        <!-- ==================== 6. 金刚区五宫格（分类 tab 位于券区之后，白面板承接金刚区；搜索模式下隐藏） ==================== -->
        <view class="cat-sheet">
          <view class="kingkong">
            <view class="kingkong-item" v-for="k in kingkongs" :key="k.key" @click="onKingkongTap(k)">
              <view class="kingkong-icon" :style="{ backgroundColor: k.bg }"><text>{{ k.emoji }}</text></view>
              <text class="kingkong-label">{{ k.name }}</text>
            </view>
          </view>
        </view>

        <!-- ==================== 7. 超级秒杀区块（搜索模式下隐藏） ==================== -->
        <view class="seckill-card">
          <view class="seckill-header">
            <text class="seckill-title">⚡ 超级秒杀</text>
            <!-- T-M3-04：归零宽限窗内直接切"已切明日场次"置灰态，避免 00:00:00 → 23:59:59 闪跳 -->
            <view class="seckill-countdown">
              <text class="cd-label" v-if="!countdownZero">仅剩</text>
              <text class="cd-value" :class="{ 'cd-zero': countdownZero }">{{ countdownZero ? '已切明日场次' : countdownText + '.' + countdownTenth }}</text>
            </view>
            <view class="seckill-go" @click="onSeckillGo"><text>去抢购 &gt;</text></view>
          </view>
          <scroll-view class="seckill-scroll" scroll-x>
            <view class="seckill-item" v-for="d in seckillDishes" :key="d.id" @click="goToDetail(d)">
              <!-- T-M3-01：真实图片（dish.image）优先，加载失败/为空回退 emoji 色块 -->
              <view class="seckill-img" :style="{ backgroundColor: d.bgColor || '#F5F5F5' }">
                <image v-if="d.image && !imgFail[d.id]" class="seckill-photo" :src="d.image" mode="aspectFill" @error="onImgFail(d.id)" />
                <text v-else class="seckill-emoji">{{ d.emoji }}</text>
              </view>
              <text class="seckill-cut">{{ d.cutText }}</text>
              <view class="seckill-price-row">
                <text class="sp-symbol">¥</text>
                <text class="sp-num">{{ d.seckillPrice }}</text>
              </view>
            </view>
            <view v-if="seckillDishes.length === 0" class="seckill-empty"><text>本场已抢空，下一场即将开始</text></view>
          </scroll-view>
        </view>

        <!-- ==================== 8. 商品列表（多多买菜卡片；搜索模式下展示搜索结果） ==================== -->
        <view class="dish-list">
          <!-- 加载失败错误态（≠空态）：点击重试（T-M3-05） -->
          <view class="error-state" v-if="loadError" @click="retryLoad">
            <text class="error-text">商品加载失败，请检查网络</text>
            <text class="error-retry">点击重试</text>
          </view>
          <template v-else>
            <view class="dish-card" v-for="dish in displayDishes" :key="dish.id" @click="goToDetail(dish)">
              <!-- 左侧 240rpx 图：真实图片（dish.image）优先，加载失败/为空回退 emoji 色块（T-M3-01） -->
              <view class="dish-img" :style="{ backgroundColor: dish.bgColor || '#F5F5F5' }">
                <image v-if="dish.image && !imgFail[dish.id]" class="dish-photo" :src="dish.image" mode="aspectFill" @error="onImgFail(dish.id)" />
                <text v-else class="dish-emoji">{{ dish.emoji }}</text>
              </view>
              <!-- 右侧信息 -->
              <view class="dish-body">
                <view class="dish-title-row">
                  <text v-if="dish.seckill" class="dish-seckill-tag">秒</text>
                  <text class="dish-name">{{ dish.name }}</text>
                </view>
                <text class="dish-tags">{{ (dish.tags || []).join(' | ') }}</text>
                <view class="dish-sold-row">
                  <text class="dish-rush">{{ dish.rushText }}</text>
                  <text class="dish-sold">{{ dish.soldText }}</text>
                </view>
                <view class="dish-promo-row">
                  <block v-if="dish.seckill">
                    <text class="tag-cut">{{ dish.cutText }}</text>
                    <text class="tag-limit">限购{{ dish.limitBuy }}件</text>
                  </block>
                  <text v-else class="dish-rate">好评率{{ dish.goodRate }}</text>
                </view>
                <!-- 价格行 + 右侧按钮/步进器 -->
                <view class="dish-price-row">
                  <view class="price-left">
                    <block v-if="dish.seckill">
                      <text class="price-seckill-label">秒杀</text>
                      <text class="price-symbol">¥</text>
                      <text class="price-big">{{ fmtPrice(dish.seckillPrice) }}</text>
                    </block>
                    <block v-else>
                      <text class="price-symbol">¥</text>
                      <text class="price-big">{{ fmtPrice(dish.price) }}</text>
                    </block>
                  </view>
                  <view class="dish-action" @click.stop>
                    <!-- 已加购：步进器 -->
                    <view v-if="cartMap[dish.id]" class="stepper">
                      <view class="step-btn step-minus" @click="minusDish(dish)"><text>−</text></view>
                      <text class="step-num">{{ cartMap[dish.id].quantity }}</text>
                      <view class="step-btn step-plus" @click="plusDish(dish)"><text>+</text></view>
                    </view>
                    <!-- 未加购：红底白字按钮 -->
                    <view v-else class="add-btn" @click="addDish(dish)">
                      <text>{{ dish.seckill ? '立即抢购' : '加入购物车' }}</text>
                    </view>
                  </view>
                </view>
              </view>
            </view>
            <!-- 空态：搜索模式"没有找到相关商品"，浏览模式"暂无商品"（搜索请求中不显示） -->
            <view v-if="!searchLoading && displayDishes.length === 0" class="empty-state">
              <text>暂无商品</text>
            </view>
          </template>
        </view>
      </view>
    </scroll-view>

    <!-- ==================== 9. 平台优惠条（仅购物车非空时，固定于购物车栏上方） ==================== -->
    <view class="platform-bar" v-if="!isCartEmpty" @click="onPlatformTap">
      <text class="pb-label">平台优惠</text>
      <text class="pb-divider">|</text>
      <text class="pb-coupon">{{ mainCouponText }}</text>
      <text class="pb-arrow">&gt;</text>
    </view>

    <!-- ==================== 10. 底部购物车栏（三段结构：🛒+角标 | 价格 | 去支付） ==================== -->
    <view class="cart-bar" v-if="!isCartEmpty">
      <view class="cart-left" @click="openCartPopup">
        <view class="cart-icon-box">
          <text class="cart-icon">🛒</text>
          <text class="cart-badge" v-if="cartCount > 0">{{ cartCount }}</text>
        </view>
          <view class="cart-info">
            <view class="cart-price-line">
              <text class="cart-total-symbol">¥</text>
              <text class="cart-total-price">{{ fmtPrice(cartTotalPrice) }}</text>
            </view>
            <text class="cart-reduced">已减 ¥{{ fmtPrice(cartSaving) }}</text>
          </view>
      </view>
      <button class="btn-pay" @click="openAddressPopup">
        <text class="pay-main">去支付</text>
        <view class="pay-sub-wrap"><text class="pay-coupon">{{ mainCouponText }}</text></view>
      </button>
    </view>

    <!-- ==================== 5. 全部分类弹层（顶部下拉：面板贴顶栏下沿，搜索栏保持可见） ==================== -->
    <view class="cat-drop" v-if="showCategoryPopup">
      <!-- 黑色半透明遮罩：压暗面板下方内容聚焦注意力，点击关闭 -->
      <view class="cat-drop-mask" @click="closeCategoryPopup"></view>
      <!-- 面板：translateY(-100%) → translateY(0) 下拉展开，0.25s 过渡 -->
      <view class="cat-drop-panel" :class="{ open: catDropOpen }" @click.stop>
        <view class="category-header">
          <text class="category-title">全部分类</text>
          <text class="popup-close" @click="closeCategoryPopup">✕</text>
        </view>
        <view class="category-grid">
          <view class="category-item" v-for="c in allCategories" :key="c.key" @click="pickCategory(c)">
            <view class="category-icon" :style="{ backgroundColor: c.bg }"><text>{{ c.emoji }}</text></view>
            <text class="category-name">{{ c.name }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- ==================== 11. 购物车弹层（多多买菜改版） ==================== -->
    <view class="popup-mask" v-if="showCartDetail" @click="closeCartPopup">
      <view class="cart-popup" @click.stop>
        <!-- 服务保障条（字典 service_tags 渲染） -->
        <view class="service-bar">
          <view class="service-item" v-for="tag in serviceTags" :key="tag">
            <text class="service-check">✓</text><text>{{ tag }}</text>
          </view>
        </view>
        <!-- 提货人行（自提点行已移除：自提点入口保留在首页顶部店铺行） -->
        <view class="pickup-row" @click="openPersonPopup">
          <text class="pr-icon">👤</text>
          <text class="pr-name">{{ pickupPerson.name }}</text>
          <text class="pr-phone">{{ pickupPerson.phone }}</text>
          <text class="pr-arrow">›</text>
        </view>
        <!-- 标题行：购物车 | 管理(退出管理) | ✕；批量删除收进管理模式底部操作条，原「清空」入口整体移除 -->
        <view class="cart-title-row">
          <text class="cart-title">购物车</text>
          <view class="cart-title-right">
            <!-- 管理入口：仅购物车非空时展示，点击进入/退出管理模式（item 勾选框 + 底部全选/删除条） -->
            <text v-if="cart.length > 0" class="cart-manage-btn" @click="toggleEdit">{{ editingCart ? '退出管理' : '管理' }}</text>
            <text class="cart-close" @click="closeCartPopup">✕</text>
          </view>
        </view>
        <!-- 凑单助手条 -->
        <view class="helper-bar">
          <text class="hb-title">凑单助手</text>
          <text class="hb-divider">|</text>
          <text class="hb-text">已减 {{ fmtPrice(cartSaving) }} 元，下单立享</text>
        </view>
        <!-- 商品行（有商品时滚动；空车时显示居中占位） -->
        <scroll-view v-if="cart.length > 0" class="cart-items" scroll-y>
          <view class="cart-item" v-for="item in cart" :key="item.id">
            <!-- 勾选圆圈（仅管理模式展示，沿用 checked/toggleChecked 勾选机制；阻止冒泡避免误触跳详情） -->
            <view v-if="editingCart" class="check-circle" :class="{ checked: item.checked }" @click.stop="toggleCheck(item)">
              <text v-if="item.checked">✓</text>
            </view>
            <!-- 图片 + 文字区域：点击跳商品详情 -->
            <view class="ci-main" @click="goToCartItemDetail(item)">
              <!-- emoji 图 -->
              <view class="ci-img" :style="{ backgroundColor: (dishOf(item).bgColor) || '#F5F5F5' }">
                <text>{{ item.emoji || dishOf(item).emoji || '🛒' }}</text>
              </view>
              <!-- 中间信息 -->
              <view class="ci-body">
                <view class="ci-name-row">
                  <text v-if="isSeckillItem(item)" class="ci-seckill-tag">秒</text>
                  <text class="ci-name">{{ item.name }}</text>
                </view>
                <view class="ci-desc-row">
                  <text class="ci-restore">即将恢复原价</text>
                  <text class="ci-sold" v-if="dishOf(item).soldText">{{ dishOf(item).soldText }}</text>
                  <text class="ci-limit" v-if="dishOf(item).limitBuy">限购{{ dishOf(item).limitBuy }}件</text>
                </view>
                <view class="ci-price-row">
                  <text class="ci-original">¥{{ fmtPrice(item.originalPrice) }}</text>
                  <view class="ci-price-line">
                    <text class="ci-symbol">¥</text>
                    <text class="ci-price">{{ fmtPrice(item.price) }}</text>
                  </view>
                  <!-- 折扣标签：仅秒杀件显示，按 unitSave/originalPrice 换算，算不出省略 -->
                  <text class="ci-discount" v-if="isSeckillItem(item) && discountRate(item)">{{ discountRate(item) }}</text>
                </view>
              </view>
            </view>
            <!-- 右侧：编辑模式删除按钮 / 步进器（阻止冒泡，避免误触跳详情） -->
            <view class="ci-action" @click.stop>
              <text v-if="editingCart" class="ci-delete" @click="removeItem(item.id)">删除</text>
              <view v-else class="stepper">
                <view class="step-btn step-minus" @click="decreaseById(item.id)"><text>−</text></view>
                <text class="step-num">{{ item.quantity }}</text>
                <view class="step-btn step-plus" @click="increaseById(item.id)"><text>+</text></view>
              </view>
            </view>
          </view>
        </scroll-view>
        <!-- 空车占位：中间区域居中展示 -->
        <view v-else class="cart-items cart-items-empty">
          <view class="cart-empty">
            <text class="cart-empty-icon">🛒</text>
            <text class="cart-empty-text">购物车还是空的，去逛逛吧</text>
          </view>
        </view>
        <!-- 管理模式底部操作条（替换结算条）：左全选（沿用 toggleAll/toggleAllChecked「非全选→全勾选、已全选→全取消」语义）+ 已选计数 + 右红「删除」（onDeleteChecked 二次确认批量删） -->
        <view class="cart-total-bar" v-if="editingCart">
          <view class="check-all" @click="toggleAll">
            <view class="check-circle" :class="{ checked: allChecked }"><text v-if="allChecked">✓</text></view>
            <text class="check-all-text">全选</text>
          </view>
          <text class="manage-count">已选 {{ checkedCount }} 件</text>
          <button class="btn-manage-delete" @click="onDeleteChecked"><text>删除</text></button>
        </view>
        <!-- 常规模式底部合计栏（无勾选框：加购默认勾选 + 退出管理时复位全选，已选恒等于全部；空车支付按钮置灰禁用） -->
        <view class="cart-total-bar" v-else>
          <text class="check-all-text">已选 {{ checkedCount }} 件</text>
          <view class="ctb-price">
            <view class="ctb-price-line">
              <text class="ci-symbol">¥</text>
              <text class="ctb-price-num">{{ fmtPrice(checkedTotalPrice) }}</text>
            </view>
            <text class="ctb-saving">已减 ¥{{ fmtPrice(cartSaving) }}</text>
          </view>
          <button class="btn-pay-popup" :class="{ 'btn-pay-disabled': cart.length === 0 }" @click="payFromCart"><text>立即支付</text></button>
        </view>
      </view>
    </view>

    <!-- ==================== 12. 修改提货人弹窗（居中白色圆角） ==================== -->
    <view class="popup-mask popup-mask-center" v-if="showPersonPopup" @click="showPersonPopup = false">
      <view class="person-popup" @click.stop>
        <text class="person-close" @click="showPersonPopup = false">✕</text>
        <text class="person-title">修改提货人信息</text>
        <view class="form-group">
          <text class="form-label">提货人</text>
          <input class="form-input" v-model="formPersonName" placeholder="请输入提货人姓名" />
        </view>
        <view class="form-group">
          <text class="form-label">手机号</text>
          <input class="form-input" v-model="formPersonPhone" type="number" maxlength="11" placeholder="请输入手机号码" />
        </view>
        <view class="person-btns">
          <button class="btn-clear-person" @click="clearPersonForm">清空</button>
          <button class="btn-save-person" @click="savePerson">保存</button>
        </view>
      </view>
    </view>

    <!-- 支付弹窗（自提模式：自提点/提货人已选好，点去支付直达，无需再填地址表单） -->
    <view class="popup-mask" v-if="showPayPopup" @click="showPayPopup = false">
      <view class="popup-panel" @click.stop>
        <view class="popup-header">
          <text class="popup-title">确认订单</text>
          <text class="popup-close" @click="showPayPopup = false">✕</text>
        </view>
        <!-- M4-T-02：加 popup-body-scroll（清单+券行内容增多时面板内部滚动，不超 70vh） -->
        <view class="popup-body popup-body-scroll">
          <view class="pay-section">
            <text class="section-title">菜品清单</text>
            <view class="pay-item" v-for="item in checkedItems" :key="item.id">
              <text class="pay-item-name">{{ item.name }}</text>
              <text class="pay-item-qty">x{{ item.quantity }}</text>
              <text class="pay-item-price">¥{{ (item.price * item.quantity).toFixed(2) }}</text>
            </view>
          </view>
          <view class="pay-section">
            <text class="section-title">自提信息</text>
            <text class="pay-info">自提点：{{ pickupPoint.name }}</text>
            <!-- M4-T-01：当前门店停业红色警示，点击跳自提点页切换 -->
            <text v-if="pickupPointClosed" class="pay-closed-warn" @click="goToPickup">⚠ 该门店暂停营业，请切换自提点 ›</text>
            <text class="pay-info">提货人：{{ pickupPerson.name }} {{ pickupPerson.phone }}</text>
            <text class="pay-info">自提地址：{{ pickupPoint.address }}</text>
            <!-- T-M3-04：自提时间动态化（倒计时 tick 内按 utils/time.js 推导维护） -->
            <text class="pay-info">自提时间：{{ pickupTimeText }}</text>
          </view>
          <!-- M4-T-02：优惠券选择行（该用户可用券，小计满足门槛可选，不满足置灰） -->
          <view class="pay-section">
            <text class="section-title">优惠券</text>
            <view v-if="availableCoupons.length === 0" class="coupon-empty">
              <text>暂无可用优惠券，可在首页领券中心领取</text>
            </view>
            <view
              v-for="c in availableCoupons"
              :key="c.id"
              class="coupon-option"
              :class="{ 'coupon-option-active': selectedCouponId === c.id, 'coupon-option-disabled': !couponUsable(c) }"
              @click="onSelectCoupon(c)"
            >
              <view class="coupon-radio"><text v-if="selectedCouponId === c.id">✓</text></view>
              <text class="coupon-option-text">满{{ fmtCouponNum(c.threshold) }}减{{ fmtCouponNum(c.amount) }}</text>
              <text class="coupon-option-state">{{ couponUsable(c) ? '可用' : '差' + fmtPrice(c.threshold - checkedTotalPrice) + '元可用' }}</text>
            </view>
          </view>
          <view class="pay-total">
            <text>合计</text>
            <!-- M4-T-02：选中有效券时显示"已抵扣 ¥Y"，合计为抵扣后金额（不低于 0） -->
            <view class="pay-total-right">
              <text v-if="couponDeduction > 0" class="pay-deducted">已抵扣 ¥{{ fmtPrice(couponDeduction) }}</text>
              <text class="pay-total-price">¥{{ fmtPrice(payableTotal) }}</text>
            </view>
          </view>
          <!-- 未登录兜底引导（防御性：正常被游客拦截不会到达此处）：轻量提示 + 跳独立登录页，登录后按 redirect 回首页续走支付 -->
          <view class="inline-login-guide" v-if="!user.token">
            <text class="ilg-tip">登录后即可完成支付</text>
            <button class="ilg-btn" @click="goLogin">去登录</button>
          </view>
          <!-- 已登录：直接立即支付（M2-T-05：下单成功后弹模拟收银台） -->
          <button v-else class="btn-confirm" :class="{ 'btn-confirm-disabled': submitting }" :disabled="submitting" @click="submitOrder">{{ submitting ? '提交中…' : '立即支付' }}</button>
        </view>
      </view>
    </view>

    <!-- ==================== 13. 模拟收银台弹窗（M2-T-05：下单成功后弹出，确认后调 mock-pay） ==================== -->
    <view class="popup-mask cashier-mask" v-if="showCashier" @click="closeCashier">
      <view class="cashier-panel" @click.stop>
        <view class="popup-header">
          <text class="popup-title">收银台</text>
          <text class="popup-close" @click="closeCashier">✕</text>
        </view>
        <view class="popup-body">
          <view class="cashier-amount-row">
            <text class="cashier-amount-label">支付金额</text>
            <view class="cashier-amount-line">
              <text class="cashier-symbol">¥</text>
              <text class="cashier-amount">{{ cashierAmount }}</text>
            </view>
          </view>
          <text class="cashier-order">订单号：{{ cashierOrderId }}</text>
          <text class="cashier-tip">模拟支付环境，点击确认即完成支付，不产生真实扣款</text>
          <button class="btn-confirm" :class="{ 'btn-confirm-disabled': paying }" :disabled="paying" @click="confirmCashierPay">{{ paying ? '支付中…' : '确认支付' }}</button>
        </view>
      </view>
    </view>

    <!-- ==================== 14. 免费领商品引导弹窗（共享组件 free-gift-guide：冷启动会话首次进入延迟 1.5s 判定弹出，活动 online 且今日未领取才弹；✕/遮罩只关本次） ==================== -->
    <free-gift-guide
      :visible="homeGuideVisible"
      :dish-name="homeGuideDish.name"
      :dish-image="homeGuideDish.image"
      :dish-emoji="homeGuideDish.emoji"
      :dish-bg="homeGuideDish.bgColor"
      :threshold="homeGuideThreshold"
      :remaining="homeGuideRemaining"
      :reached="homeGuideReached"
      @close="closeHomeGuide"
      @go-order="goHomeGuideOrder"
    />
  </view>
</template>

<script>
import { mapState, mapGetters } from 'vuex'
import { submitOrder, mockPay, decorateDish, getSeckillCurrent, getMyCoupons, claimCoupon, freeCurrent } from '@/api/index.js'
import { getDictGroup, parseCoupon } from '@/api/dict.js'
import { BASE_URL } from '@/api/config.js'
import { getCutoffInfo, formatCountdown, formatPickupTimeText, monotonicNow, parseServerTimeMs, sessionEndMs } from '@/utils/time.js'

// 分类弹层视觉映射：字典只提供名称与 code，emoji/圆底色按 code 本地映射补齐
const CATEGORY_STYLE = {
  all: { emoji: '⭐', bg: '#FFF1F0' },
  meat: { emoji: '🥩', bg: '#FFECE8' },
  vegetable: { emoji: '🥦', bg: '#EAF7EE' },
  fastfood: { emoji: '🍜', bg: '#FFF3E0' },
  wine: { emoji: '🍺', bg: '#E8F3FF' },
  fruit: { emoji: '🍎', bg: '#FFF1F0' },
  fresh: { emoji: '🐟', bg: '#E8F7FF' },
  frozen: { emoji: '🧊', bg: '#E8F3FF' },
  snack: { emoji: '🍪', bg: '#FFF3E0' },
  dairy: { emoji: '🥛', bg: '#FDE8F0' },
  grain: { emoji: '🍚', bg: '#FFFBE6' },
  general: { emoji: '🧻', bg: '#F4F4F5' }
}
// 金刚区视觉映射（key 与字典 definition 的 code:xxx 对应）
const KINGKONG_STYLE = {
  seckill: { emoji: '⚡', bg: '#FFECE8' },
  free: { emoji: '🎁', bg: '#FFF3E0' },
  coupon_center: { emoji: '🎟️', bg: '#FDE8F0' },
  invite: { emoji: '💰', bg: '#E8F3FF' },
  more: { emoji: '🧩', bg: '#EAF7EE' }
}
// 优惠券状态文案映射（state 由 parseCoupon 解析得到；未知 state 兜底"即将上线"，不再出现占位文案）
const COUPON_STATUS = { available: '立即使用', locked1: '1单后可领', locked2: '2单后可领' }

// ==================== 免费领商品引导弹窗会话标记（sessionStorage 'freeGuideHomeShown'） ====================
// 每次冷启动会话首次进入只弹一次：H5 用 window.sessionStorage（同一浏览器会话含刷新只弹一次）；
// 小程序无 sessionStorage，退化为模块级内存标记（App 冷启动即新会话，语义等价，纯 JS 判空不写 #ifdef）
const HOME_GUIDE_KEY = 'freeGuideHomeShown'
let homeGuideShownMemory = false
// 会话标记读取：优先 sessionStorage；隐私模式等存储异常按未弹过处理
function homeGuideShownRead() {
  try {
    if (typeof window !== 'undefined' && window.sessionStorage) {
      return window.sessionStorage.getItem(HOME_GUIDE_KEY) === '1'
    }
  } catch (e) { /* 存储异常按未弹过处理 */ }
  return homeGuideShownMemory
}
// 会话标记写入：在"决定弹"的时刻写入（此后即使立刻关闭，本次会话也不再弹）；失败时内存标记已兜底
function homeGuideShownWrite() {
  homeGuideShownMemory = true
  try {
    if (typeof window !== 'undefined' && window.sessionStorage) {
      window.sessionStorage.setItem(HOME_GUIDE_KEY, '1')
    }
  } catch (e) { /* 存储异常静默 */ }
}

export default {
  data() {
    return {
      // 当前分类：'all'(推荐-不过滤) | 字典 code（meat/vegetable/fruit/...，T-M3-12 全量真实过滤）
      currentCategory: 'all',
      // 全量商品（一次拉取全部分类，前端本地过滤；分类数据对齐字典 code）
      dishes: [],
      // 商品列表加载失败错误态（≠空态，显示"点击重试"，T-M3-05）
      loadError: false,
      // 图片加载失败记录表（id -> true）：@error 后该卡回退 emoji 色块（T-M3-01）
      imgFail: {},
      // 超级秒杀倒计时（23:00 截单逻辑，十分位；时间计算走 utils/time.js 共用逻辑）
      countdownText: '',
      countdownTenth: '0',
      // 归零展示态：刚跨过 23:00 的 2s 宽限窗内为 true，直接切"已切明日场次"置灰，避免闪跳（T-M3-04）
      countdownZero: false,
      countdownTimer: null,
      // 截单前 1 小时（22:00-23:00）顶部红条显隐（T-M3-04）
      showCutoffSoon: false,
      // 自提时间动态文案（推导自 utils/time.js，随 23:00 截单口径变化，T-M3-04）
      pickupTimeText: '',
      // 弹层/弹窗开关
      showCategoryPopup: false, // 全部分类弹层
      catDropOpen: false,       // 全部分类面板展开动画状态（translateY 过渡）
      // 搜索区变色态：滚动到达"分类 tab 吸顶位"（tab 文档偏移 - 搜索区高度）后为 true，搜索区由红底切白底
      catTabStuck: false,
      // 一体式搜索白条内真实 input 的绑定值
      // 搜索模式（T-M3-02）：true 时列表展示 searchResults，隐藏金刚区/秒杀区/分类 tab
      searchResults: [],        // 搜索结果（getDishes?keyword= 同源接口，decorateDish 增强）
      searchLoading: false,     // 搜索请求飞行中（空态在请求中不闪现）
      showCartDetail: false,    // 购物车弹层
      showPersonPopup: false,   // 修改提货人弹窗
      showPayPopup: false,      // 支付弹窗（原有流程）
      // 模拟收银台（M2-T-05）：下单成功后弹出，确认支付调 mock-pay
      showCashier: false,
      cashierOrderId: '',       // 待支付订单号（服务端雪花 id）
      cashierAmount: '0.00',    // 待支付金额（优先服务端重算 totalPrice）
      paying: false,            // 收银台支付中防重锁
      // 下单防重锁（T-M1-09）：提交飞行中为 true，"立即支付"按钮禁用态
      submitting: false,
      // 幂等键 clientRequestId（≤64 字符）：一次确认订单会话生成一次，
      // 失败重试沿用同一键（服务端幂等返回首次订单，断网重试不重复建单），成功/重开会话后换新
      clientRequestId: '',
      // 购物车管理模式（item 勾选 + 底部全选/批量删除条）
      editingCart: false,
      // 提货人表单
      formPersonName: '',
      formPersonPhone: '',
      // 地址表单（原有流程）
      // 秒杀锚点滚动位置（scroll-view scroll-top）
      mainScrollTop: 0,
      // 优惠券三卡（字典 coupon_template 渲染：word 显示、parseCoupon 拆 threshold/amount/state）
      coupons: [],
      // 金刚区五宫格（字典 kingkong_entry 渲染）
      kingkongs: [],
      // 全部分类弹层网格（字典 dish_category 渲染）
      allCategories: [],
      // 服务保障标签（字典 service_tags 渲染，购物车弹层服务条）
      serviceTags: ['坏了包退', '晚到必赔', '极速退款'],
      // === M4-T-03 秒杀场次（服务端时钟） ===
      // true=倒计时基准为 /api/seckill/current 的 serverTime+end；接口未上线/失败回退本地 23:00 口径
      seckillServerMode: false,
      // 当前场次商品（接口 dishes 优先；空时回退本地列表过滤）
      seckillSessionDishes: [],
      // === M4-T-02 优惠券 ===
      // 当前登录用户券列表（GET /api/coupons/my；used 券在 availableCoupons 过滤）
      myCoupons: [],
      // 选中的券 id（null=不使用；小计变化时 watch 自动重置）
      selectedCouponId: null,
      // === 免费领商品引导弹窗（共享组件 free-gift-guide）：冷启动会话首次进入延迟 1.5s 判定弹出 ===
      homeGuideVisible: false,     // 显隐（数据就绪后再置 true，避免闪空）
      homeGuideDish: { name: '', image: '', emoji: '🎁', bgColor: '#FFF3E0' }, // 弹窗商品（pool[0]/dish 归一化）
      homeGuideThreshold: 0,       // 满额门槛（元）
      homeGuideRemaining: 0,       // 距门槛差额（元，后端 remainingToThreshold）
      homeGuideReached: false      // 是否已达门槛（remaining<=0，切换副标题文案）
    }
  },
  computed: {
    ...mapState(['cart', 'pickupPoint', 'pickupPerson', 'address', 'phone', 'user']),
    ...mapGetters(['cartCount', 'cartTotalPrice', 'isCartEmpty', 'cartSaving', 'checkedCount', 'checkedTotalPrice']),
    // 列表商品：'all'（推荐）时不过滤但秒杀商品优先（T-M3-13 排序语义），其余按字典 code 过滤（T-M3-12）
    filteredDishes() {
      let list
      if (this.currentCategory === 'all') {
        // 推荐 tab：全量 + seckill 优先（组内保持原有 id 倒序稳定排序）
        const sec = this.dishes.filter(d => d.seckill)
        const rest = this.dishes.filter(d => !d.seckill)
        list = sec.concat(rest)
      } else {
        list = this.dishes.filter(d => d.category === this.currentCategory)
      }
      return list
    },
    // 列表实际数据源：搜索模式展示搜索结果，浏览模式展示分类过滤结果（T-M3-02）
    displayDishes() {
      return this.filteredDishes
    },
    // 热词：默认取当前商品名前 5（本地兜底，点击可再搜）
    // 秒杀商品（横向滚动区）：M4-T-03 优先场次接口 dishes（服务端真实秒杀集），回退本地列表过滤
    seckillDishes() {
      if (this.seckillSessionDishes.length > 0) return this.seckillSessionDishes
      return this.dishes.filter(d => d.seckill)
    },
    // 购物车 id -> 项 的映射，用于列表判断"已加购"与数量
    cartMap() {
      const map = {}
      this.cart.forEach(item => { map[item.id] = item })
      return map
    },
    // 全量商品 id -> 商品 的映射，用于购物车行回查 seckill/limitBuy/soldText/bgColor
    dishById() {
      const map = {}
      this.dishes.forEach(d => { map[d.id] = d })
      return map
    },
    // 勾选商品（支付弹窗清单与下单使用）
    checkedItems() { return this.cart.filter(item => item.checked) },
    // 是否全部勾选（控制合计栏全选圆圈）
    allChecked() { return this.cart.length > 0 && this.cart.every(item => item.checked) },
    // 联系信息展示（原有确认订单流程）
    // 平台优惠条/支付按钮券文案：优先取第一条可用券，按 threshold/amount 拼“满x减y”
    mainCouponText() {
      const list = this.coupons || []
      if (!list.length) return ''
      const c = list.find(x => !x.locked) || list[0]
      if (c.threshold && c.amount) return `满${c.threshold}减${c.amount}`
      return c.main || ''
    },
    // ==================== M4-T-01/T-M4-02 计算属性 ====================
    // 当前自提门店停业（确认订单自提信息区红色警示；status 快照由接口同步/选店写入）
    pickupPointClosed() {
      return !!(this.pickupPoint && this.pickupPoint.status === 'closed')
    },
    // 可用券列表（仅 status=available；used 券不展示）
    availableCoupons() {
      return (this.myCoupons || []).filter(c => c && (c.status || 'available') === 'available')
    },
    // 当前选中且仍满足门槛的券（小计变化导致不满足时自动失效不抵扣）
    selectedCoupon() {
      if (this.selectedCouponId == null) return null
      const c = this.availableCoupons.find(x => x.id === this.selectedCouponId)
      return c && this.couponUsable(c) ? c : null
    },
    // 已抵扣金额（选中有效券时为券面额）
    couponDeduction() {
      return this.selectedCoupon ? Number(this.selectedCoupon.amount) || 0 : 0
    },
    // 抵扣后应付合计（不低于 0，与服务端重算口径一致；未用券时等于勾选小计）
    payableTotal() {
      return Math.max(0, +(Number(this.checkedTotalPrice) - this.couponDeduction).toFixed(2))
    }
  },
  onLoad() {
    this.loadDishes()
    this.loadDicts()
    this.startCountdown()
  },
  onShow() {
    // M4-T-03：进入/返回页面拉取当前秒杀场次（倒计时基准=服务端时钟；切场后数据同样经此刷新）
    this.loadSeckillSession()
    // 独立搜索页"去支付"跳回首页（reLaunch）：读 openCheckout 标志打开确认订单弹窗（购物车非空时），用后即清。
    // openAddressPopup 内含空车/未勾选校验、幂等键复位与可用券拉取，与首页 cart-bar"去支付"同链路
    if (uni.getStorageSync('openCheckout') === '1') {
      uni.removeStorageSync('openCheckout')
      if (!this.isCartEmpty) this.openAddressPopup()
    }
  },
  onReady() {
    // 内容首次渲染后测量"tab 吸顶位"阈值（驱动 catTabStuck 变色 + 弹层滚动目标；券区异步渲染后 loadDicts 内会重测校准）
    this.measureStickyThreshold()
    // 免费领商品引导弹窗：冷启动会话首次进入，延迟 1.5s 判定弹出（不挡首屏渲染）
    this.scheduleHomeGuide()
  },
  onUnload() {
    if (this.countdownTimer) clearInterval(this.countdownTimer)
    // 归零展示态定时器、搜索防抖定时器一并清理
    if (this._zeroTimer) { clearTimeout(this._zeroTimer); this._zeroTimer = null }
    if (this._searchDebounceTimer) { clearTimeout(this._searchDebounceTimer); this._searchDebounceTimer = null }
    // 免费领引导延迟定时器一并清理
    if (this._homeGuideTimer) { clearTimeout(this._homeGuideTimer); this._homeGuideTimer = null }
    // H5：兜底移除物理返回守卫（正常路径由 showPayPopup watch 移除）
    // #ifdef H5
    window.removeEventListener('popstate', this.onPayPopGuard)
    // #endif
  },
  watch: {
    // M4-T-02：小计变化时已选券自动重置（门槛可能不再满足，避免提交失效 couponId）
    checkedTotalPrice() {
      if (this.selectedCouponId != null) this.selectedCouponId = null
    },
    // T-M3-08：确认订单弹窗开启时拦截物理返回（H5 popstate 方案）——
    // 开启时 pushState 占位一条历史记录；用户按返回先触发 popstate 关弹窗而非离开页面；
    // 手动关闭（✕/遮罩/流程完成）时主动 history.back() 消耗占位记录，避免下一次返回多按一次
    showPayPopup(open) {
      // #ifdef H5
      if (open) {
        this._payGuardConsumed = false
        try {
          history.pushState({ uniPayGuard: true }, '')
          this._payGuardPushed = true
        } catch (e) {
          this._payGuardPushed = false
        }
        window.addEventListener('popstate', this.onPayPopGuard)
      } else {
        window.removeEventListener('popstate', this.onPayPopGuard)
        // popstate 消耗掉的占位无需再回退（否则会真的离开页面）
        if (this._payGuardPushed && !this._payGuardConsumed) {
          try { history.back() } catch (e) { /* 无历史记录时忽略 */ }
        }
        this._payGuardPushed = false
      }
      // #endif
    }
  },
  methods: {
    // ==================== 数据加载 ====================
    // 页面局部请求（T-M3-02/T-M3-12/T-M3-13）：api/index.js 归另一位同事维护，为避免文件冲突，
    // 全量列表与关键词搜索在页面内用 uni.request 直连（复用 config.js BASE_URL 与 api 层 decorateDish 增强）。
    // 建议后续由 api/index.js 统一导出：getDishesAll() / searchDishes(keyword)
    dishRequest(params) {
      return new Promise((resolve, reject) => {
        uni.request({
          url: BASE_URL + '/api/dishes',
          method: 'GET',
          data: params || {},
          timeout: 10000,
          success: (res) => {
            const body = res.data
            if (res.statusCode === 200 && body && body.code === 200 && Array.isArray(body.data)) {
              resolve(body.data.map(decorateDish))
            } else {
              reject({ code: body && body.code != null ? body.code : res.statusCode, message: (body && body.message) || '服务开小差了，请稍后重试' })
            }
          },
          fail: () => reject({ code: -1, message: '网络异常' })
        })
      })
    },
    async loadDishes() {
      this.loadError = false
      try {
        // 一次拉全量（不带 category 返回全部，天然覆盖对齐字典 code 后的所有分类），
        // 各分类真实过滤在前端本地完成（T-M3-12：点任意分类即出对应商品）
        const all = await this.dishRequest({})
        // 与后端排序习惯保持一致（按 id 倒序）
        all.sort((a, b) => b.id - a.id)
        this.dishes = all
      } catch (e) {
        // 拉取失败显示错误态（≠空态），列表区出现"点击重试"（T-M3-05）
        this.dishes = []
        this.loadError = true
      }
    },
    // 错误态重试（T-M3-05）
    retryLoad() {
      if (this.loadError) this.loadDishes()
    },
    // 图片加载失败：标记该商品回退 emoji 色块（T-M3-01，emoji 字段保留不删）
    onImgFail(id) {
      this.imgFail = { ...this.imgFail, [id]: true }
    },
    // 金额统一两位小数展示（T-M3-07）：脏数据（NaN/undefined）兜底 0.00
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // ==================== 秒杀场次（M4-T-03：服务端时钟倒计时） ====================
    // 拉取当前场次：以 serverTime 与本机钟差锚定场次剩余毫秒，tick 走单调时钟递减（改本机时钟不影响）；
    // end 过点由 tick 触发 onSeckillSessionEnd 重拉接口（切场）。失败回退本地 23:00 截单口径
    async loadSeckillSession() {
      if (this._seckillFetching) return false
      this._seckillFetching = true
      try {
        const res = await getSeckillCurrent()
        const data = (res && res.data) || {}
        const serverNowMs = parseServerTimeMs(data.serverTime)
        const endMs = sessionEndMs(data.start, data.end, serverNowMs)
        if (serverNowMs == null || endMs == null) {
          this.seckillServerMode = false
          return false
        }
        this._seckillMono0 = monotonicNow()
        this._seckillRemaining0 = Math.max(0, endMs - serverNowMs)
        this.seckillServerMode = true
        // 场次商品（服务端真实秒杀集，decorate 后与列表卡片同构；空数组保持回退本地过滤）
        this.seckillSessionDishes = (Array.isArray(data.dishes) ? data.dishes : []).map(decorateDish)
        return true
      } catch (e) {
        // 接口未上线(404)/网络异常：回退本地口径
        this.seckillServerMode = false
        return false
      } finally {
        this._seckillFetching = false
      }
    },
    // 切场（end 过点）：置灰"已切明日场次"并重拉接口；重拉失败 2s 后回退本地口径不闪跳
    async onSeckillSessionEnd() {
      if (this._seckillSwitching) return
      // 3s 冷却：服务端场次数据异常导致 end 已过时，防止连环重拉接口
      const nowMono = monotonicNow()
      if (this._lastSwitchAt && nowMono - this._lastSwitchAt < 3000) return
      this._lastSwitchAt = nowMono
      this._seckillSwitching = true
      this.countdownZero = true
      const ok = await this.loadSeckillSession()
      if (ok) {
        this.countdownZero = false
      } else {
        setTimeout(() => {
          this.countdownZero = false
          this.seckillServerMode = false
        }, 2000)
      }
      this._seckillSwitching = false
    },
    // ==================== 倒计时（23:00 截单，十分位；时间计算统一走 utils/time.js，T-M3-04） ====================
    // 页面仅保留自己的 100ms 定时器；M4-T-03：服务端场次模式下倒计时基准为
    // /api/seckill/current 的 serverTime+end（服务端时钟+单调时钟递减）；回退模式目标 = 下一个 23:00；
    // 跨过截单/切场瞬间切"已切明日场次"置灰 2s 后重置，不闪跳
    startCountdown() {
      const tick = () => {
        // 归零展示态期间冻结数字，由 _zeroTimer 在 2s 后恢复
        if (this.countdownZero) return
        const info = getCutoffInfo()
        // 顺带维护：截单前 1 小时红条显隐 + 自提时间动态文案（时段取自提点 timeText、日期词按截单口径动态替换）
        this.showCutoffSoon = info.cutoffSoon
        this.pickupTimeText = formatPickupTimeText(this.pickupPoint && this.pickupPoint.timeText, info)
        // M4-T-03：服务端场次模式——倒计时基准为接口 serverTime+end
        if (this.seckillServerMode) {
          const remaining = this._seckillRemaining0 - (monotonicNow() - this._seckillMono0)
          if (remaining <= 0) { this.onSeckillSessionEnd(); return }
          const cd = formatCountdown(remaining)
          this.countdownText = cd.text
          this.countdownTenth = cd.tenth
          return
        }
        // 本地回退：原 23:00 截单口径（接口未上线/拉取失败时兜底）
        // 刚跨过今日 23:00（2s 宽限窗内）：归零帧直接切"已切明日场次"置灰态
        if (info.justPassedCutoff) { this.enterCountdownZero(info); return }
        this.isAfterCutoff = info.isAfterCutoff
        const cd = formatCountdown(info.diffMs)
        this.countdownText = cd.text
        this.countdownTenth = cd.tenth
      }
      tick()
      this.countdownTimer = setInterval(tick, 100)
    },
    // 归零展示态：置灰显示"已切明日场次"2s，随后重算（目标已是明天 23:00，无缝续走正常倒计时）
    enterCountdownZero(info) {
      this.countdownZero = true
      this.isAfterCutoff = !!(info && info.isAfterCutoff)
      if (this._zeroTimer) clearTimeout(this._zeroTimer)
      this._zeroTimer = setTimeout(() => {
        this._zeroTimer = null
        this.countdownZero = false
      }, 2000)
    },
    // ==================== 字典加载 ====================
    async loadDicts() {
      // 并行拉取四组字典；getDictGroup 失败时内部自动回退内置默认，此处仅做空数组兜底
      const [cats, cps, kks, tags] = await Promise.all([
        getDictGroup('dish_category').catch(() => []),
        getDictGroup('coupon_template').catch(() => []),
        getDictGroup('kingkong_entry').catch(() => []),
        getDictGroup('service_tags').catch(() => [])
      ])
      this.allCategories = this.mapCategories(cats)
      this.coupons = this.mapCoupons(cps)
      this.kingkongs = this.mapKingkongs(kks)
      // 服务保障标签（购物车弹层服务条用）
      this.serviceTags = (tags || []).map(d => d.word)
      // 券区三卡异步渲染会改变 tab 之前的文档高度，等 DOM 更新后重测吸顶阈值校准变色时机
      this.$nextTick(() => { this.measureStickyThreshold() })
    },
    // definition "code:xxx" -> "xxx"
    codeOf(def) { return String(def || '').replace(/^code:/, '').trim() },
    // 字典分类 -> 弹层网格项：word 作名称、code 作 key，emoji/底色本地映射补齐
    mapCategories(list) {
      return (list || []).map(d => {
        const key = this.codeOf(d.definition)
        const fb = CATEGORY_STYLE[key] || {}
        return { key, name: d.word, emoji: fb.emoji || '🏷️', bg: fb.bg || '#F4F4F5' }
      })
    },
    // 字典券 -> 三卡：state 决定文案与锁定态（available 可点，locked1/locked2 锁样式）；
    // M4-T-02：couponKey 按固定模板 coupon_1/coupon_2/coupon_3 与字典 coupon_template 排序一一对应
    mapCoupons(list) {
      return (list || []).map((d, i) => {
        const c = parseCoupon(d.definition) || {}
        return {
          main: d.word,
          couponKey: `coupon_${i + 1}`,
          threshold: c.threshold,
          amount: c.amount,
          state: c.state || '',
          locked: c.state !== 'available',
          status: COUPON_STATUS[c.state] || '即将上线'
        }
      })
    },
    // 字典金刚区 -> 五宫格：code 作 key（onKingkongTap 按 key 分发：秒杀/领券滚动定位、其他开分类弹层、活动类占位）
    mapKingkongs(list) {
      return (list || []).map(d => {
        const key = this.codeOf(d.definition)
        const fb = KINGKONG_STYLE[key] || {}
        return { key, name: d.word, emoji: fb.emoji || '🧩', bg: fb.bg || '#F4F4F5' }
      })
    },
    // ==================== 搜索区变色阈值 + tab 吸顶滚动目标 ====================
    // _tabStickyScrollTop = tab 在滚动内容中的文档偏移 - 搜索区自身高度（实测 res[0].height，即 16*2rpx 内边距 + 64rpx 搜索框 = 96rpx）。
    // tab 的 sticky top 正是该高度，scrollTop 到达此值时 tab 恰好顶到搜索框下沿（sticky 生效时刻），同步驱动搜索区红→白；
    // 该值同时作为 openCategoryPopup 的滚动目标（滚到 tab 吸顶位再下拉面板）
    measureStickyThreshold() {
      const q = uni.createSelectorQuery().in(this)
      q.select('.sticky-header').boundingClientRect()
      q.select('.cat-tab-bar').boundingClientRect()
      q.select('.main-scroll').boundingClientRect()
      q.select('.main-scroll').scrollOffset()
      q.exec(res => {
        if (!res || !res[0] || !res[1] || !res[2] || !res[3]) return
        const st = res[3].scrollTop
        const headerHeight = res[0].height || 0
        const tabTopRel = res[1].top - res[2].top
        // tab 已吸附时其视口位置被 sticky 钳在搜索框下沿，反推文档偏移会失真，跳过本次测量保留旧值
        if (tabTopRel <= headerHeight + 1) return
        this._tabStickyScrollTop = Math.max(0, st + tabTopRel - headerHeight)
      })
    },
    // 滚动回调：记录实时位置驱动吸顶态（_curScrollTop 不入 data，避免高频渲染）
    onMainScroll(e) {
      const st = (e && e.detail && e.detail.scrollTop) || 0
      this._curScrollTop = st
      // tab 吸附判定：tab 顶部是否已被 sticky 钳到搜索框下沿。
      // 直接对比两个元素的实际位置，与真实吸顶状态天然同步（不依赖常量换算，避免 header 实测高与 sticky top 不一致导致变色滞后）
      if (this._stuckCheckTimer) return
      this._stuckCheckTimer = setTimeout(() => {
        this._stuckCheckTimer = null
        const q = uni.createSelectorQuery().in(this)
        q.select('.sticky-header').boundingClientRect()
        q.select('.cat-tab-bar').boundingClientRect()
        q.exec(res => {
          if (!res || !res[0] || !res[1]) return
          const stuck = res[1].top - res[0].bottom <= 1
          if (stuck !== this.catTabStuck) this.catTabStuck = stuck
        })
      }, 80)
    },
    // ==================== 通用 ====================
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // 物理返回守卫回调（T-M3-08）：确认订单/收银台开着时按返回先关弹窗，不离开页面
    onPayPopGuard() {
      // #ifdef H5
      this._payGuardConsumed = true
      if (this.showCashier) { this.closeCashier(); return }
      if (this.showPayPopup) this.showPayPopup = false
      // #endif
    },
    goToDetail(dish) { uni.navigateTo({ url: `/pages/detail/detail?id=${dish.id}` }) },
    goToPickup() { uni.navigateTo({ url: '/pages/pickup/pickup' }) },
    goToOrder() { uni.navigateTo({ url: '/pages/order/order' }) },
    // 「我的」入口：跳个人中心页（身份卡/优惠券/评价/账号安全/退出登录）
    goToMine() { uni.navigateTo({ url: '/pages/mine/mine' }) },
    // ==================== 1. 搜索（搜索页接管版） ====================
    // 点击搜索框/搜索按钮/回车：跳转独立搜索页 pages/search/search（搜索交互全部在搜索页内完成）
    onSearch() {
      uni.navigateTo({ url: '/pages/search/search' })
    },
    // ==================== 3. 优惠券三卡 ====================
    // M4-T-02：点击领券（POST /api/coupons/claim，登录，固定模板 coupon_1/2/3）；
    // 锁定券提示获取条件；未登录引导；重复领取等失败透出后端 message
    async onCouponTap(c) {
      if (c.locked) { this.showToast(`${c.main}：下单${c.status}`); return }
      if (!this.user.token) { this.showToast('请先登录后领券'); return }
      if (this._claiming) return
      this._claiming = true
      try {
        await claimCoupon(c.couponKey)
        uni.showToast({ title: '领取成功，下单可抵扣', icon: 'none', duration: 2000 })
      } catch (e) {
        // 重复领取（后端 400"该券已领取"/同 key 仅 1 张可用）等：透出 message
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this._claiming = false
      }
    },
    // ==================== 确认订单优惠券行（M4-T-02） ====================
    // 拉取当前用户券列表（GET /api/coupons，登录）；未登录不发；401（登录态失效，request 层
    // 已清态）/网络异常均静默兜底空列表，不打扰下单主流程
    async loadMyCoupons() {
      if (!this.user.token) { this.myCoupons = []; return }
      try {
        const res = await getMyCoupons()
        this.myCoupons = Array.isArray(res && res.data) ? res.data : []
      } catch (e) {
        this.myCoupons = []
      }
    },
    // 券是否满足门槛（勾选小计 >= threshold 可选，否则置灰不可点）
    couponUsable(c) {
      return Number(this.checkedTotalPrice) >= Number(c && c.threshold)
    },
    // 点选/取消优惠券：不满足门槛置灰拦截并提示差额
    onSelectCoupon(c) {
      if (!this.couponUsable(c)) {
        const gap = Number(c.threshold) - Number(this.checkedTotalPrice)
        this.showToast(`未满足满减条件，还差¥${this.fmtPrice(gap)}`)
        return
      }
      this.selectedCouponId = this.selectedCouponId === c.id ? null : c.id
    },
    // 券面金额展示（去尾零：19.00→19 / 4.50→4.5，脏数据按 0）
    fmtCouponNum(v) {
      const n = Number(v)
      return Number.isFinite(n) ? String(+n.toFixed(2)) : '0'
    },
    // ==================== 免费领商品引导弹窗（共享组件 free-gift-guide） ====================
    // 触发规则：每次冷启动会话首次进入弹一次（sessionStorage 'freeGuideHomeShown'，标记在决定弹时写入，
    // 此后关闭也不复弹）；进入（onReady）后延迟 1.5s 再判定，不挡首屏渲染；弹出前提=活动 online 且
    // 今日未领取（登录态口径取 GET /api/free/current 的 claimed，匿名无 token 时后端 claimed=false 照常弹）；
    // 已领取/活动下线/请求失败均不弹且不写标记（失败下次进页可重试）。onShow/购物车/管理模式零改动。
    scheduleHomeGuide() {
      if (homeGuideShownRead()) return // 本次会话已弹过（或已决定弹过），不再弹
      if (this._homeGuideTimer) clearTimeout(this._homeGuideTimer)
      this._homeGuideTimer = setTimeout(() => {
        this._homeGuideTimer = null
        this.showHomeGuide()
      }, 1500)
    },
    // 延迟到点判定：拉 current（匿名可调），online 且未领取才填充数据弹窗
    async showHomeGuide() {
      // 1.5s 内已切走其它页（如点了金刚区进免费页）：不补弹不写标记，避免回首页二次打扰
      const pages = getCurrentPages()
      const top = pages[pages.length - 1]
      if (top && top.$vm && top.$vm !== this) return
      try {
        const res = await freeCurrent()
        const data = (res && res.data) || null
        if (!data) return
        // 活动开放状态（口径与免费页一致：activityStatus 优先，兼容旧 status；均缺省按有商品视为 online）
        let status = data.activityStatus
        if (typeof status !== 'string' || !status) status = data.status
        if (!status) status = (data.dish || (Array.isArray(data.pool) && data.pool.length)) ? 'online' : 'empty'
        if (status !== 'online') return
        // 今日已领取不弹（claimed 优先，兼容旧 claimedToday）；匿名视为未领取照常弹
        const claimed = data.claimed === true || Number(data.claimed) === 1 ||
          data.claimedToday === true || Number(data.claimedToday) === 1
        if (claimed) return
        // 弹窗商品：候选池第一个优先，回退 dish 单商品（与免费页 poolList 同款兜底）
        const pool = Array.isArray(data.pool) ? data.pool : []
        const dish = pool[0] || (data.dish && typeof data.dish === 'object' ? data.dish : null)
        if (!dish) return
        // 门槛与差额（后端 remainingToThreshold 优先，缺省按 threshold - todayPaid 本地兜底，两位小数钳零）
        const threshold = Number(data.threshold)
        this.homeGuideThreshold = Number.isFinite(threshold) && threshold > 0 ? threshold : 50
        let rem = Number(data.remainingToThreshold)
        if (!Number.isFinite(rem) || rem < 0) {
          const paid = Number(data.todayPaid)
          rem = this.homeGuideThreshold - (Number.isFinite(paid) ? paid : 0)
        }
        this.homeGuideRemaining = Math.max(0, +Number(rem).toFixed(2))
        this.homeGuideReached = this.homeGuideRemaining <= 0
        this.homeGuideDish = {
          name: dish.name || '免费商品',
          image: dish.image || '',
          emoji: dish.emoji || '🎁',
          bgColor: dish.bgColor || '#FFF3E0'
        }
        homeGuideShownWrite() // 决定弹：写会话标记（即使立刻关闭，本次会话也不再弹）
        this.homeGuideVisible = true
      } catch (e) {
        // 请求失败静默不弹且不写标记（下次进页可重试）
      }
    },
    closeHomeGuide() { this.homeGuideVisible = false },
    // 引导弹窗「去下单」：关弹窗跳免费领商品页（首页语义=带用户去领免费商品）
    goHomeGuideOrder() {
      this.homeGuideVisible = false
      uni.navigateTo({ url: '/pages/free/free' })
    },
    // ==================== 9. 平台优惠条 ====================
    onPlatformTap() { this.showToast(`${this.mainCouponText}，已在购物车生效`) },
    // ==================== 4. 分类 tab ====================
    switchCategory(cat) {
      this.currentCategory = cat
      this.$nextTick(() => { this.measureStickyThreshold() })
    },
    // ==================== 6. 金刚区 ====================
    // 五入口按 key 分发：秒杀滚到秒杀区；领券中心滚到券区（领取链路 onCouponTap 已真实）；
    // 免费领商品跳独立领取页 pages/free/free；邀请赚现金跳 pages/invite/cash（F-04.3）；
    // 其他打开全部分类弹层
    onKingkongTap(k) {
      if (k.key === 'seckill') { this.scrollToSeckill(); return }
      if (k.key === 'coupon_center') { this.scrollToCouponBanner(); return }
      if (k.key === 'free') { uni.navigateTo({ url: '/pages/free/free' }); return }
      if (k.key === 'invite') { uni.navigateTo({ url: '/pages/invite/cash' }); return }
      if (k.key === 'more') { this.openCategoryPopup(); return }
      this.showToast('活动即将上线，敬请期待')
    },
    // 平滑滚动到秒杀区块：以 scroll-view 实际偏移计算目标 scroll-top
    scrollToSeckill() {
      const query = uni.createSelectorQuery().in(this)
      query.select('.seckill-card').boundingClientRect()
      query.select('.main-scroll').boundingClientRect()
      query.select('.main-scroll').scrollOffset()
      query.exec(res => {
        if (!res || !res[0] || !res[1] || !res[2]) return
        const target = res[2].scrollTop + (res[0].top - res[1].top) - 10
        this.mainScrollTop = Math.max(0, target)
      })
    },
    // 平滑滚动到优惠券三卡区：以 scroll-view 实际偏移计算目标 scroll-top（与 scrollToSeckill 同款）
    scrollToCouponBanner() {
      const query = uni.createSelectorQuery().in(this)
      query.select('.coupon-banner').boundingClientRect()
      query.select('.main-scroll').boundingClientRect()
      query.select('.main-scroll').scrollOffset()
      query.exec(res => {
        if (!res || !res[0] || !res[1] || !res[2]) return
        const target = res[2].scrollTop + (res[0].top - res[1].top) - 10
        this.mainScrollTop = Math.max(0, target)
      })
    },
    // ==================== 7. 秒杀区"去抢购" ====================
    onSeckillGo() { this.showToast('正在疯抢，先到先得！') },
    // ==================== 5. 全部分类弹层（先平滑滚到 tab 吸顶位，再顶部下拉动画） ====================
    openCategoryPopup() {
      // 滚动目标 = tab 吸顶位（滚动到券区底部贴住搜索框下沿，tab 吸附在搜索框正下方）
      const th = this._tabStickyScrollTop || 0
      const cur = typeof this._curScrollTop === 'number' ? this._curScrollTop : 0
      const doOpen = () => {
        // 快速重复开关时清掉未结束的关闭定时器，避免面板被误卸载
        if (this._catDropTimer) { clearTimeout(this._catDropTimer); this._catDropTimer = null }
        this.showCategoryPopup = true
        this.catDropOpen = false
        // 先以收起态（translateY(-100%)）渲染，下一拍再加 open 类触发 0.25s 下拉过渡
        this.$nextTick(() => {
          setTimeout(() => { this.catDropOpen = true }, 20)
        })
      }
      // 偏离 tab 吸顶位时先平滑滚动（scroll-with-animation）过去，到位后再下拉展开面板
      if (Math.abs(cur - th) > 2) {
        // :scroll-top 绑定值需发生变化才生效：先同步当前实际位置，再设目标位置
        this.mainScrollTop = cur
        this.$nextTick(() => { this.mainScrollTop = th })
        setTimeout(doOpen, 320)
      } else {
        doOpen()
      }
    },
    closeCategoryPopup() {
      // 先回退动画（translateY → -100%），过渡 0.25s 结束后再卸载面板
      this.catDropOpen = false
      if (this._catDropTimer) clearTimeout(this._catDropTimer)
      this._catDropTimer = setTimeout(() => {
        this.showCategoryPopup = false
        this._catDropTimer = null
      }, 250)
    },
    pickCategory(c) {
      // T-M3-12：所有字典分类点击即真实过滤（'all'=推荐不过滤+秒杀优先，其余按字典 code 过滤
      // 全量商品），无任何 toast 占位；分类唯一真源=字典（allCategories 由 dish_category 组渲染）
      this.switchCategory(c.key)
      this.closeCategoryPopup()
    },
    // ==================== 8. 列表加购/步进器 ====================
    limitOf(id) {
      const d = this.dishById[id]
      return d && d.limitBuy ? d.limitBuy : 0
    },
    // 未加购 -> 首次加入（走 addToCart normalize）
    addDish(dish) { this.$store.commit('addToCart', dish) },
    // 列表步进器 "+"
    plusDish(dish) {
      if (!this.cartMap[dish.id]) { this.addDish(dish); return }
      this.increaseById(dish.id)
    },
    // 列表步进器 "−"：减到 0 直接移除
    minusDish(dish) { this.decreaseById(dish.id) },
    // 通用加一（列表/购物车弹层共用）：已达限购拦截 toast；加到限购数量时同样提示
    increaseById(id) {
      const item = this.cartMap[id]
      if (!item) return
      const limit = this.limitOf(id)
      const next = item.quantity + 1
      if (limit && item.quantity >= limit) {
        this.showToast(`限购${limit}件`)
        return
      }
      this.$store.commit('updateQuantity', { dishId: id, quantity: next })
      // "+" 到限购数量时 toast 限购x件
      if (limit && next === limit) this.showToast(`限购${limit}件`)
    },
    // 通用减一：减到 0 直接 removeFromCart
    decreaseById(id) {
      const item = this.cartMap[id]
      if (!item) return
      if (item.quantity <= 1) {
        this.$store.commit('removeFromCart', id)
      } else {
        this.$store.commit('updateQuantity', { dishId: id, quantity: item.quantity - 1 })
      }
    },
    // ==================== 10. 购物车栏 ====================
    openCartPopup() { this.showCartDetail = true },
    closeCartPopup() {
      this.showCartDetail = false
      this.editingCart = false
    },
    // ==================== 11. 购物车弹层 ====================
    // 管理/退出管理模式：退出时复位全选——常规展示无 item 勾选框（加购默认勾选），
    // 若管理模式内取消勾选未删除就退出，常规模式下会残留无法修改的未勾选项，导致结算金额与列表不符
    toggleEdit() {
      this.editingCart = !this.editingCart
      if (!this.editingCart) {
        this.cart.forEach(item => { if (!item.checked) this.$store.commit('toggleChecked', item.id) })
      }
    },
    toggleCheck(item) { this.$store.commit('toggleChecked', item.id) },
    toggleAll() { this.$store.commit('toggleAllChecked') },
    // 编辑模式单件删除：删光时一并退出管理模式，避免空列表残留底部管理操作条
    removeItem(id) {
      this.$store.commit('removeFromCart', id)
      if (this.cart.length === 0) this.editingCart = false
    },
    // 管理模式批量删除：未勾选轻提示拦截；showModal 二次确认后循环提交 removeFromCart（store 无批量 mutation），
    // 勾选状态随 item 移除自然清空，同时退出管理模式回到常规展示
    onDeleteChecked() {
      if (this.checkedCount === 0) { this.showToast('请先勾选要删除的商品'); return }
      uni.showModal({
        title: '删除商品',
        content: `是否删除选中的 ${this.checkedCount} 件商品？`,
        confirmText: '删除',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.checkedItems.forEach(item => this.$store.commit('removeFromCart', item.id))
          this.editingCart = false
        }
      })
    },
    // 购物车行图片+文字区域点击跳商品详情：先复位弹层开关再跳转
    goToCartItemDetail(item) {
      this.showCartDetail = false
      this.editingCart = false
      uni.navigateTo({ url: '/pages/detail/detail?id=' + item.id })
    },
    // 购物车行回查原始商品（seckill/limitBuy/soldText/bgColor 在契约购物车项上没有，需回查）
    dishOf(item) { return this.dishById[item.id] || {} },
    // 购物车行 [秒] 标判定：优先回查商品，回查不到按 unitSave>1 兜底
    isSeckillItem(item) {
      const d = this.dishOf(item)
      if (d && typeof d.seckill === 'boolean') return d.seckill
      return item.unitSave > 1
    },
    // 折扣标签：按 unitSave/originalPrice 换算（如 7.6折），算不出返回空串省略
    discountRate(item) {
      const orig = Number(item.originalPrice)
      const save = Number(item.unitSave)
      if (!orig || orig <= 0 || !isFinite(save) || save < 0) return ''
      const paid = orig - save
      if (paid <= 0) return ''
      const zhe = (paid / orig) * 10
      if (zhe <= 0 || zhe >= 10) return ''
      return zhe.toFixed(1).replace(/\.0$/, '') + '折'
    },
    // 合计栏"立即支付"：游客下单拦截——加购不拦，结算未登录先引导去独立登录页（带 redirect 回首页续走）；
    // 空车按钮置灰但保留点击提示；先校验勾选，再走原有地址确认流程
    payFromCart() {
      // 未登录判定与确认订单弹窗引导块一致（!user.token）；登录后 decodeURIComponent(redirect) 回首页
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/index/index') })
          }
        })
        return
      }
      if (this.cart.length === 0) { this.showToast('请先添加商品'); return }
      if (this.checkedCount === 0) { this.showToast('请先勾选商品'); return }
      this.closeCartPopup()
      this.openAddressPopup()
    },
    // ==================== 原有确认订单流程 ====================
    // 自提模式：自提点/提货人已就绪，点去支付直达确认订单（不再弹地址表单）
    openAddressPopup() {
      // 底部购物车栏"去支付"：游客下单拦截——先弹登录引导确认框，确认后跳独立登录页（带 redirect 回首页续走），不打开确认订单弹层
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/index/index') })
          }
        })
        return
      }
      if (this.isCartEmpty) { this.showToast('购物车为空'); return }
      if (this.checkedCount === 0) { this.showToast('请先勾选商品'); return }
      // 每次新开确认订单弹窗换新幂等键（上一次会话的键不带入）
      this.clientRequestId = ''
      // M4-T-02：每次打开确认订单拉取该用户可用券（已选券随券列表刷新自动重置）
      this.loadMyCoupons()
      this.showPayPopup = true
    },
    // 生成幂等键：时间戳 + 随机串（base36），长度远小于 64 上限
    genClientRequestId() {
      return Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
    },
    // 下单三分支（T-M1-06/T-M1-09 + M2-T-05）：成功弹模拟收银台 / 业务失败 toast 保留现场 / 网络异常提示可重试
    async submitOrder() {
      // 防重锁：请求飞行中重复点击直接 return（配合按钮禁用态，双击仅 1 个请求）
      if (this.submitting) return
      const phone = (this.pickupPerson && this.pickupPerson.phone) || ''
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请先填写提货人手机号'); return }
      if (!this.checkedItems.length) { this.showToast('请先勾选商品'); return }
      // 幂等键：本会话尚未生成时才生成（失败重试/401 重登后续走沿用同一键，服务端幂等防重复建单；
      // 收银台支付成功或下次新开会话时才重置）
      if (!this.clientRequestId) this.clientRequestId = this.genClientRequestId()
      // body 为 OrderCreateDTO：金额服务端按 dish 现价重算，前端仅传 dishId + quantity
      const orderData = {
        clientRequestId: this.clientRequestId,
        phone,
        items: this.checkedItems.map(item => ({ dishId: item.id, quantity: item.quantity }))
      }
      // M4-T-01：自提门店快照来源（服务端落 store_id + store_name/store_address 快照列）
      if (this.pickupPoint && this.pickupPoint.storeId != null) orderData.storeId = this.pickupPoint.storeId
      // M4-T-02：使用优惠券（服务端校验归属/status=available/满减门槛后抵扣重算；
      // 不满足门槛 400"未满足满减条件"由下方 catch toast 保留现场）
      if (this.selectedCoupon) orderData.couponId = this.selectedCoupon.id
      this.submitting = true
      try {
        const res = await submitOrder(orderData)
        // 分支一：code:200 成功 —— 不直接关流程，弹"模拟收银台"
        // （订单号取服务端雪花 id，金额以服务端重算 totalPrice 为准，缺失兜底前端勾选合计）
        const order = (res && res.data) || {}
        this.cashierOrderId = order.id != null ? String(order.id) : ''
        this.cashierAmount = order.totalPrice != null ? Number(order.totalPrice).toFixed(2) : Number(this.checkedTotalPrice).toFixed(2)
        // M4-T-02：下单成功券已核销（服务端置 used + 回写 order_id），本地清选中并刷新券列表
        this.selectedCouponId = null
        this.loadMyCoupons()
        // 下单已扣减库存：立即重拉商品与秒杀场次，列表/秒杀区数据随服务端真实值同步
        // （不 await，不阻塞收银台弹出；销量文案联动需后端下单同步自增 sold_count 后刷新才可见）
        this.loadDishes()
        this.loadSeckillSession()
        this.showCashier = true
      } catch (e) {
        // 分支二（400 业务校验/库存不足等；401 未登录——request 层已清登录态，
        // 确认订单弹窗内未登录引导块响应式复现）与分支三（code:-1 网络异常/超时）：
        // 一律 toast 后端 message，不清购物车、不关弹窗，保留现场可重试
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.submitting = false
      }
    },
    // ==================== 未登录兜底引导（防御性：正常被游客拦截不可达） ====================
    // 跳独立登录页（验证码/密码/设置密码），登录成功后按 redirect 回首页续走支付
    goLogin() {
      uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/index/index') })
    },
    // ==================== 模拟收银台（M2-T-05） ====================
    // 确认支付：调 POST /api/pay/{orderId}/mock-pay（幂等，已支付重复调用直接返回成功）
    // → 成功 toast 支付成功 + 关弹窗 + 清购物车；失败 toast message 保留现场
    async confirmCashierPay() {
      if (this.paying || !this.cashierOrderId) return
      this.paying = true
      // #ifdef MP-WEIXIN
      // 小程序端支付分叉预留（T-M4-04）：真实环境应先调后端统一下单/预支付接口换取支付参数，
      // 再调 uni.requestPayment({ provider: 'wxpay', timeStamp, nonceStr, package, signType, paySign, ... })
      // 拉起微信支付，支付回调成功后再查询订单确认；当前小程序与 H5 统一走模拟支付 mock-pay 现状
      // await uni.requestPayment({ provider: 'wxpay', ... })
      // #endif
      try {
        await mockPay(this.cashierOrderId)
        uni.showToast({ title: '支付成功', icon: 'success' })
        this.closeCashier()
        this.showPayPopup = false
        this.$store.commit('clearCart')
        // 支付成功即下单闭环完成：重拉商品与秒杀场次，列表/秒杀区数据随服务端真实值同步
        // （不 await，不阻塞弹窗关闭与购物车清空渲染；销量文案联动需后端下单同步自增 sold_count 后刷新才可见）
        this.loadDishes()
        this.loadSeckillSession()
        // 支付会话结束，幂等键用毕重置（下次新开确认订单再生成新键）
        this.clientRequestId = ''
      } catch (e) {
        // 401（登录态失效）：request 层已清登录态，收起收银台露出确认订单弹窗，未登录引导块响应式复现；
        // 其余失败 toast 后端 message，收银台保留现场可重试
        if (e && e.code === 401) this.closeCashier()
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.paying = false
      }
    },
    // 关闭收银台（未支付）：订单已创建（待支付），可稍后在订单页"去支付"
    closeCashier() {
      this.showCashier = false
    },
    // ==================== 12. 修改提货人弹窗 ====================
    openPersonPopup() {
      this.formPersonName = this.pickupPerson.name
      this.formPersonPhone = this.pickupPerson.phone
      this.showPersonPopup = true
    },
    savePerson() {
      const name = this.formPersonName.trim()
      const phone = this.formPersonPhone.trim()
      if (!name) { this.showToast('请输入提货人姓名'); return }
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      this.$store.commit('setPickupPerson', { name, phone })
      this.showPersonPopup = false
      this.showToast('保存成功')
    },
    // 清空提货人表单：仅清弹窗内输入值，便于重新填写（不动已保存的 pickupPerson）
    clearPersonForm() {
      this.formPersonName = ''
      this.formPersonPhone = ''
    }
  }
}
</script>

<style scoped>
/* ==================== 多多买菜风格 — 首页 ==================== */

.page-container {
  position: fixed; top: var(--window-top, 0px); left: 0; right: 0; bottom: var(--window-bottom, 0px);
  display: flex; flex-direction: column; overflow: hidden;
  background-color: #F5F5F5;
}

/* ==================== 吸顶层（仅搜索框；分类 tab 已移至券区之后独立吸顶，样式见下方 4 区块） ==================== */
/* 原生 sticky：置于 scroll-view 内容顶部，父链无 overflow:hidden，H5 下相对最近滚动容器吸附；
   未滚动红底 #E02020，分类 tab 吸顶时刻（catTabStuck → .stuck）切换白底 */
.sticky-header {
  position: sticky; top: 0; z-index: 90;
  background-color: #E02020;
}
.sticky-header.stuck {
  background-color: #FFFFFF;
  box-shadow: 0 4rpx 12rpx rgba(0,0,0,.08);
}
/* 搜索行（背景随吸顶层两态切换，自身不再固定红底） */
.red-header {
  display: flex; align-items: center;
  padding: 16rpx 24rpx;
}
/* 一体式搜索白条：圆角 40rpx 高 64rpx，内排 🔍 + input(flex:1) + 竖分隔线 + "搜索"红字（不再独立胶囊） */
.search-box {
  flex: 1; min-width: 0;
  box-sizing: border-box;
  display: flex; align-items: center; gap: 10rpx;
  height: 64rpx; padding: 0 24rpx;
  background-color: #FFFFFF; border-radius: 40rpx;
  overflow: hidden;
}
/* 吸附态（白底）：白条改红描边 1rpx 以便区分（border-box 下总高仍 64rpx，96rpx 吸顶位不变） */
.sticky-header.stuck .search-box {
  border: 1rpx solid #E02020;
}
.search-icon { flex-shrink: 0; font-size: 24rpx; }
/* 真实 input（H5 编译为 uni-input）：placeholder 颜色走 placeholder-style，输入文字深灰 */
.search-input {
  flex: 1; min-width: 0; height: 64rpx; line-height: 64rpx;
  font-size: 26rpx; color: #333333;
}
/* 竖分隔线：1rpx 浅灰高 32rpx，两态均显示（白条底色不变，浅灰始终可见） */
.search-divider {
  flex-shrink: 0;
  width: 1rpx; height: 32rpx;
  background-color: #EEEEEE;
}
/* "搜索"：红字加粗 28rpx，点击区域 padding 0 28rpx */
.search-go {
  flex-shrink: 0; align-self: stretch;
  display: flex; align-items: center;
  padding: 0 28rpx;
}
.search-go-text { font-size: 28rpx; font-weight: 700; color: #E02020; }

/* ==================== 1.5 搜索辅助区（历史词 + 热词胶囊，T-M3-02） ==================== */

/* 主滚动区（纯白背景；吸顶层 sticky 置于内容顶部，滚走后视口内只剩白底） */
.main-scroll { flex: 1; min-height: 0; background-color: #FFFFFF; }
/* 底部预留 300rpx（购物车栏 120rpx + 平台优惠条 60rpx + 余量），避免固定栏遮住最后一张卡片 */
.scroll-inner { padding-bottom: 300rpx; }

/* ==================== 2+3. 红色横幅区 ==================== */
.red-zone {
  /* 首子元素（.cutoff-bar 的 margin-top:12rpx）margin 塌陷会穿透红区形成白缝，flow-root 建立 BFC 使 12rpx 间距留在红区内呈红底 */
  display: flow-root;
  background: linear-gradient(180deg, #E02020 0%, #D31D1D 100%);
  padding-bottom: 28rpx;
}
/* 截单前 1 小时红条（T-M3-04）：黄底深字在红区中醒目，由倒计时 tick 维护显隐 */
.cutoff-bar {
  margin: 12rpx 24rpx 0;
  display: flex; align-items: center; justify-content: center;
  background-color: rgba(255, 216, 77, .95);
  border-radius: 10rpx;
  padding: 10rpx 20rpx;
}
.cutoff-bar-text { font-size: 22rpx; font-weight: 600; color: #7A2B00; }
/* 店铺行（白色小字行） */
.store-row {
  display: flex; align-items: center; gap: 10rpx;
  padding: 20rpx 24rpx 12rpx;
}
.store-icon { font-size: 28rpx; flex-shrink: 0; }
.store-name {
  flex: 1; min-width: 0;
  font-size: 24rpx; font-weight: 600; color: #FFFFFF;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.store-service {
  flex-shrink: 0;
  font-size: 18rpx; color: #FFFFFF;
  border: 1rpx solid rgba(255,255,255,.7); border-radius: 6rpx;
  padding: 2rpx 10rpx;
}
.store-order {
  flex-shrink: 0; margin-left: 8rpx;
  font-size: 24rpx; font-weight: 600; color: #FFFFFF;
  background-color: rgba(255,255,255,.16);
  border-radius: 24rpx; padding: 6rpx 22rpx;
}
/* 优惠券三卡 */
.coupon-banner { padding: 8rpx 24rpx 0; }
.coupon-cards { display: flex; gap: 12rpx; }
.coupon-card {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12rpx;
  height: 128rpx;
  background-color: rgba(255,255,255,.14);
  border: 1rpx solid rgba(255,255,255,.35);
  border-radius: 12rpx;
}
.coupon-main { font-size: 26rpx; font-weight: 700; color: #FFFFFF; }
.coupon-use-btn {
  font-size: 20rpx; font-weight: 700; color: #E02020;
  background-color: #FFFFFF; border-radius: 20rpx;
  padding: 4rpx 18rpx;
}
.coupon-lock { font-size: 18rpx; color: rgba(255,255,255,.75); }
.coupon-tip {
  display: block; margin-top: 14rpx;
  font-size: 20rpx; color: rgba(255,255,255,.75); text-align: center;
}

/* ==================== 4. 分类 tab（券区之后、金刚区之前；始终白底深色字，推荐红字高亮+红下划线） ==================== */
/* 自身 sticky：top = 搜索区高度（16*2rpx 内边距 + 64rpx 搜索框 = 96rpx），滚动后吸附在搜索框正下方；
   高度 88rpx、上下无 margin/padding，与券区和金刚区紧凑衔接 */
.cat-tab-bar {
  position: sticky; top: 96rpx; z-index: 89;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24rpx; height: 88rpx;
  background-color: #FFFFFF;
}
.cat-tabs { display: flex; gap: 44rpx; }
.cat-tab { position: relative; padding: 12rpx 0; }
.cat-tab text { font-size: 30rpx; font-weight: 700; color: #666666; }
.cat-tab.active text { color: #E02020; }
.cat-tab.active::after {
  content: ''; position: absolute; bottom: 2rpx; left: 50%; transform: translateX(-50%);
  width: 44rpx; height: 6rpx; background-color: #E02020; border-radius: 3rpx;
}
.cat-all text { font-size: 26rpx; color: #666666; }

/* ==================== 6. 金刚区（白色面板直接衔接分类 tab；tab 独立后不再负 margin 叠压红区） ==================== */
.cat-sheet {
  position: relative;
  background-color: #FFFFFF;
  border-radius: 20rpx 20rpx 0 0;
}
/* 金刚区 */
.kingkong { display: flex; padding: 8rpx 12rpx 24rpx; }
.kingkong-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 10rpx; }
.kingkong-icon {
  width: 88rpx; height: 88rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.kingkong-icon text { font-size: 44rpx; }
.kingkong-label { font-size: 22rpx; color: #333333; }

/* ==================== 7. 超级秒杀区块 ==================== */
.seckill-card {
  margin: 20rpx 24rpx;
  border-radius: 16rpx; overflow: hidden;
  background: linear-gradient(160deg, #E02020 0%, #F0402F 60%, #FF7A50 100%);
}
.seckill-header {
  display: flex; align-items: center;
  padding: 20rpx 24rpx 12rpx;
}
.seckill-title { flex-shrink: 0; font-size: 32rpx; font-weight: 700; color: #FFFFFF; }
.seckill-countdown {
  display: flex; align-items: center; gap: 8rpx;
  margin-left: 16rpx;
  background-color: rgba(0,0,0,.28); border-radius: 8rpx; padding: 4rpx 12rpx;
}
.cd-label { font-size: 20rpx; color: #FFFFFF; }
.cd-value { font-size: 24rpx; font-weight: 700; color: #FFFFFF; font-variant-numeric: tabular-nums; }
/* 归零展示态：置灰"已切明日场次"（T-M3-04 归零不闪跳） */
.cd-value.cd-zero { color: rgba(255,255,255,.72); font-weight: 600; font-size: 22rpx; }
.seckill-go { margin-left: auto; flex-shrink: 0; }
.seckill-go text { font-size: 24rpx; color: #FFFFFF; font-weight: 600; }
.seckill-scroll { white-space: nowrap; padding: 8rpx 0 20rpx; }
.seckill-scroll ::v-deep .uni-scroll-view::-webkit-scrollbar { display: none; }
.seckill-item {
  display: inline-block; width: 180rpx; margin-left: 16rpx;
  background-color: #FFFFFF; border-radius: 12rpx; overflow: hidden;
  vertical-align: top;
}
.seckill-img {
  width: 100%; height: 140rpx;
  display: flex; align-items: center; justify-content: center;
}
.seckill-emoji { font-size: 64rpx; }
/* 秒杀卡真实图片（T-M3-01）：铺满色块容器，@error 回退 emoji */
.seckill-photo { width: 100%; height: 100%; display: block; }
.seckill-cut {
  display: inline-block; margin: 8rpx 0 0 12rpx;
  font-size: 18rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 6rpx; padding: 2rpx 10rpx;
}
.seckill-price-row { display: flex; align-items: baseline; padding: 6rpx 12rpx 14rpx; gap: 2rpx; }
.sp-symbol { font-size: 20rpx; font-weight: 700; color: #E02020; }
.sp-num { font-size: 32rpx; font-weight: 700; color: #E02020; line-height: 1; }
.seckill-empty { display: inline-block; width: 100%; text-align: center; padding: 40rpx 0; }
.seckill-empty text { font-size: 24rpx; color: rgba(255,255,255,.85); }

/* ==================== 8. 商品列表 ==================== */
.dish-list { padding: 0 24rpx; }
.dish-card {
  display: flex;
  background-color: #FFFFFF; border-radius: 12rpx;
  border: 1rpx solid #EEEEEE; box-sizing: border-box;
  padding: 16rpx; margin-bottom: 16rpx;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.dish-img {
  width: 240rpx; height: 240rpx; flex-shrink: 0;
  border-radius: 8rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.dish-emoji { font-size: 100rpx; }
/* 商品卡真实图片（T-M3-01）：铺满 240rpx 色块容器，@error 回退 emoji */
.dish-photo { width: 100%; height: 100%; display: block; }
.dish-body {
  flex: 1; min-width: 0; margin-left: 20rpx;
  display: flex; flex-direction: column;
}
.dish-title-row { display: flex; align-items: center; min-width: 0; }
.dish-seckill-tag {
  flex-shrink: 0; margin-right: 8rpx;
  font-size: 18rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 4rpx; padding: 0 8rpx;
  line-height: 30rpx;
}
.dish-name {
  flex: 1; min-width: 0;
  font-size: 28rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-tags {
  margin-top: 8rpx;
  font-size: 20rpx; color: #999999;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-sold-row { display: flex; align-items: center; gap: 12rpx; margin-top: 8rpx; }
.dish-rush { font-size: 20rpx; font-weight: 600; color: #E02020; flex-shrink: 0; }
.dish-sold { font-size: 20rpx; color: #999999; }
.dish-promo-row { display: flex; align-items: center; gap: 10rpx; margin-top: 8rpx; min-height: 30rpx; }
.tag-cut {
  font-size: 18rpx; color: #E02020; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 6rpx; padding: 0 8rpx;
  line-height: 26rpx;
}
.tag-limit {
  font-size: 18rpx; color: #999999;
  border: 1rpx solid #DDDDDD; border-radius: 6rpx; padding: 0 8rpx;
  line-height: 26rpx;
}
.dish-rate { font-size: 20rpx; color: #999999; }
.dish-price-row {
  margin-top: auto;
  display: flex; align-items: flex-end; justify-content: space-between;
}
.price-left { display: flex; align-items: baseline; min-width: 0; }
.price-seckill-label { font-size: 20rpx; font-weight: 700; color: #E02020; margin-right: 4rpx; }
.price-symbol { font-size: 20rpx; font-weight: 700; color: #E02020; }
.price-big { font-size: 38rpx; font-weight: 700; color: #E02020; line-height: 1; }
.dish-action { flex-shrink: 0; margin-left: 16rpx; }
.add-btn {
  min-width: 160rpx; height: 56rpx; padding: 0 22rpx; box-sizing: border-box;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #FF5A3C, #E02020);
  border-radius: 28rpx;
}
.add-btn text { font-size: 22rpx; font-weight: 700; color: #FFFFFF; }
/* 步进器（列表/购物车弹层共用） */
.stepper { display: flex; align-items: center; }
.step-btn {
  width: 46rpx; height: 46rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.step-btn text { font-size: 30rpx; font-weight: 700; line-height: 1; }
.step-minus { border: 1rpx solid #E02020; background-color: #FFFFFF; }
.step-minus text { color: #E02020; }
.step-plus { background-color: #E02020; }
.step-plus text { color: #FFFFFF; }
.step-num { min-width: 56rpx; text-align: center; font-size: 26rpx; font-weight: 700; color: #333333; }

.empty-state { display: flex; align-items: center; justify-content: center; padding: 120rpx 0; }
.empty-state text { font-size: 26rpx; color: #CCCCCC; }

/* 搜索结果头（T-M3-02）：关键词 + 取消 */

/* 加载失败错误态（T-M3-05，≠空态）：文案 + 点击重试按钮 */
.error-state { display: flex; flex-direction: column; align-items: center; gap: 20rpx; padding: 120rpx 0; }
.error-text { font-size: 26rpx; color: #999999; }
.error-retry {
  font-size: 26rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 32rpx;
  padding: 10rpx 44rpx;
}

/* ==================== 9. 平台优惠条 ==================== */
.platform-bar {
  position: fixed; left: 0; right: 0; bottom: 120rpx; z-index: 99;
  height: 60rpx;
  display: flex; align-items: center; gap: 12rpx;
  padding: 0 24rpx;
  background-color: #FFFFFF;
  border-top: 1rpx solid #F0F0F0;
}
.pb-label { font-size: 22rpx; font-weight: 700; color: #333333; }
.pb-divider { font-size: 22rpx; color: #DDDDDD; }
.pb-coupon { font-size: 22rpx; font-weight: 600; color: #E02020; }
.pb-arrow { margin-left: auto; font-size: 22rpx; color: #999999; }

/* ==================== 10. 底部购物车栏（三段结构，保持直角大红块） ==================== */
.cart-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 120rpx;
  background-color: #FFFFFF; display: flex; align-items: stretch;
  overflow: hidden;
  box-shadow: 0 -2rpx 16rpx rgba(0,0,0,.08); z-index: 100;
}
.cart-left { display: flex; align-items: center; gap: 12rpx; padding: 0 16rpx 0 24rpx; flex: 2; min-width: 0; }
.cart-icon-box { position: relative; width: 52rpx; height: 52rpx; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cart-icon { font-size: 44rpx; line-height: 1; }
.cart-badge {
  position: absolute; top: -4rpx; right: -4rpx;
  min-width: 30rpx; height: 30rpx; padding: 0 6rpx; box-sizing: border-box;
  background-color: #E02020; border-radius: 15rpx;
  font-size: 20rpx; color: #FFFFFF; text-align: center; line-height: 30rpx;
}
.cart-info { display: flex; flex-direction: column; justify-content: center; gap: 4rpx; margin-left: auto; min-width: 0; }
.cart-price-line { display: flex; align-items: baseline; }
.cart-total-symbol { font-size: 26rpx; color: #E02020; font-weight: 700; }
.cart-total-price { font-size: 40rpx; font-weight: 700; color: #E02020; line-height: 1; }
.cart-reduced { font-size: 20rpx; color: #999999; }
.btn-pay {
  flex: 3; min-width: 0; margin: 0;
  background-color: #E02E24;
  color: #FFFFFF; border: none; border-radius: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6rpx;
  padding: 16rpx; line-height: 1.3;
}
.pay-main { font-size: 30rpx; font-weight: 700; color: #FFFFFF; }
.pay-sub-wrap { display: flex; align-items: center; }
.pay-coupon { font-size: 22rpx; background-color: #FFD84D; color: #C23A0F; border-radius: 8rpx; padding: 2rpx 10rpx; font-weight: 600; }

/* ==================== 弹窗通用 ==================== */
.popup-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5); z-index: 200;
  display: flex; align-items: flex-end; justify-content: center;
}
/* 居中弹窗（修改提货人） */
.popup-mask-center { align-items: center; z-index: 300; }
.popup-panel { width: 100%; background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0; max-height: 70vh; }
.popup-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 28rpx 32rpx; border-bottom: 1rpx solid #F0F0F0;
}
.popup-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.popup-close { font-size: 32rpx; color: #CCCCCC; padding: 0 8rpx; }
.popup-body { padding: 32rpx; }

/* ==================== 5. 全部分类弹层（顶部下拉） ==================== */
/* 容器贴顶栏下沿（顶栏高 = 16*2rpx 内边距 + 64rpx 搜索框 = 96rpx，搜索栏保持可见）；
   overflow:hidden 让收起态面板（translateY(-100%)）在容器上沿外被裁剪不可见 */
.cat-drop {
  position: fixed; top: 96rpx; left: 0; right: 0; bottom: 0; z-index: 200;
  overflow: hidden;
}
/* 黑色半透明遮罩：压暗面板下方内容让注意力聚焦分类面板，点击关闭 */
.cat-drop-mask {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5);
}
/* 面板：纯白背景与页面白底自然衔接，仅保留轻微投影；
   默认收起到容器上沿之外，.open 后下拉就位（关闭时同一过渡回退） */
.cat-drop-panel {
  position: absolute; top: 0; left: 0; right: 0; z-index: 1;
  background-color: #FFFFFF; border-radius: 0 0 24rpx 24rpx;
  box-shadow: 0 12rpx 24rpx rgba(0,0,0,.06);
  transform: translateY(-100%);
  transition: transform .25s ease;
  overflow: hidden;
  padding-bottom: 20rpx;
}
.cat-drop-panel.open { transform: translateY(0); }
.category-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 28rpx 32rpx 8rpx;
}
.category-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.category-grid { display: flex; flex-wrap: wrap; padding: 12rpx 12rpx 8rpx; }
.category-item {
  width: 25%; box-sizing: border-box;
  display: flex; flex-direction: column; align-items: center;
  padding: 20rpx 0;
}
.category-icon {
  width: 96rpx; height: 96rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.category-icon text { font-size: 48rpx; }
.category-name { margin-top: 12rpx; font-size: 22rpx; color: #333333; }

/* ==================== 11. 购物车弹层 ==================== */
.cart-popup {
  width: 100%;
  background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0;
  height: 80vh; /* 固定 80% 屏高，空车/有车一致（420x780 视口下约 624px） */
  display: flex; flex-direction: column;
  overflow: hidden;
}
/* 服务保障条（固定头部，不滚动） */
.service-bar {
  flex-shrink: 0;
  display: flex; justify-content: space-around; align-items: center;
  background-color: #F5F5F5; padding: 14rpx 24rpx;
}
.service-item { display: flex; align-items: center; gap: 4rpx; font-size: 20rpx; color: #666666; }
.service-check { color: #07C160; font-weight: 700; }
/* 提货人行（固定头部，不滚动；自提点行已移除） */
.pickup-row {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 10rpx;
  padding: 20rpx 24rpx 4rpx;
}
.pr-icon { font-size: 28rpx; flex-shrink: 0; }
.pr-name {
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pr-phone { font-size: 24rpx; color: #666666; }
.pr-arrow { margin-left: auto; flex-shrink: 0; font-size: 30rpx; color: #CCCCCC; }
/* 标题行（固定头部，不滚动） */
.cart-title-row {
  flex-shrink: 0;
  display: flex; align-items: center; justify-content: space-between;
  padding: 20rpx 24rpx 4rpx;
}
.cart-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.cart-title-right { display: flex; align-items: center; gap: 28rpx; }
/* 管理入口：灰字（红留给管理模式内的勾选态与删除按钮），进入管理模式后文案变"退出管理" */
.cart-manage-btn { font-size: 24rpx; font-weight: 600; color: #666666; }
.cart-close { font-size: 32rpx; color: #CCCCCC; }
/* 凑单助手条（固定头部，不滚动） */
.helper-bar {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 12rpx;
  margin: 12rpx 24rpx 0; padding: 10rpx 20rpx;
  background-color: #FFF7F0; border-radius: 10rpx;
}
.hb-title { font-size: 22rpx; font-weight: 700; color: #E02020; }
.hb-divider { font-size: 22rpx; color: #F0D8C0; }
.hb-text { font-size: 22rpx; color: #E0662A; }
/* 商品行滚动区（flex:1 占满剩余高度内部滚动；空车时兼作居中占位容器） */
.cart-items { flex: 1; min-height: 0; padding: 0 24rpx; box-sizing: border-box; }
.cart-items-empty { display: flex; align-items: center; justify-content: center; }
.cart-item {
  display: flex; align-items: center; gap: 16rpx;
  padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5;
}
.check-circle {
  width: 36rpx; height: 36rpx; flex-shrink: 0;
  border: 2rpx solid #DDDDDD; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background-color: #FFFFFF;
}
.check-circle.checked { border: none; background-color: #E02020; }
.check-circle text { font-size: 20rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
/* 图片+文字区域：整体可点跳商品详情（勾选圈与右侧操作区已阻止冒泡） */
.ci-main { flex: 1; min-width: 0; display: flex; align-items: center; gap: 16rpx; }
.ci-img {
  width: 120rpx; height: 120rpx; flex-shrink: 0;
  border-radius: 8rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.ci-img text { font-size: 56rpx; }
.ci-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.ci-name-row { display: flex; align-items: center; min-width: 0; }
.ci-seckill-tag {
  flex-shrink: 0; margin-right: 8rpx;
  font-size: 16rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 4rpx; padding: 0 6rpx;
  line-height: 26rpx;
}
.ci-name {
  flex: 1; min-width: 0;
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ci-desc-row { display: flex; align-items: center; gap: 12rpx; overflow: hidden; white-space: nowrap; }
.ci-restore { font-size: 20rpx; font-weight: 600; color: #E02020; flex-shrink: 0; }
.ci-sold { font-size: 20rpx; color: #999999; flex-shrink: 0; }
.ci-limit { font-size: 20rpx; color: #999999; }
.ci-price-row { display: flex; align-items: baseline; gap: 12rpx; }
.ci-original { font-size: 20rpx; color: #BBBBBB; text-decoration: line-through; }
.ci-price-line { display: flex; align-items: baseline; }
.ci-symbol { font-size: 18rpx; font-weight: 700; color: #E02020; }
.ci-price { font-size: 30rpx; font-weight: 700; color: #E02020; line-height: 1; }
.ci-discount {
  font-size: 18rpx; font-weight: 600; color: #E02020;
  background-color: #FFECE8; border-radius: 6rpx; padding: 2rpx 8rpx;
}
.ci-action { flex-shrink: 0; }
.ci-delete {
  display: inline-block;
  font-size: 22rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 24rpx;
  padding: 6rpx 20rpx;
}
/* 空购物车占位（由 .cart-items-empty 水平垂直居中） */
.cart-empty { display: flex; flex-direction: column; align-items: center; gap: 16rpx; }
.cart-empty-icon { font-size: 88rpx; opacity: .4; }
.cart-empty-text { font-size: 26rpx; color: #999999; }
/* 底部合计栏（固定面板底部，不滚动） */
.cart-total-bar {
  flex-shrink: 0;
  display: flex; align-items: center;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid #F0F0F0;
}
.check-all { display: flex; align-items: center; gap: 10rpx; flex-shrink: 0; }
.check-all-text { font-size: 24rpx; color: #333333; }
/* 管理模式底部操作条：全选圈复用 check-circle 红勾选态，右侧红「删除」批量按钮（显式覆盖 uni-button 默认圆角/行高/边框） */
.manage-count { margin-left: 20rpx; font-size: 24rpx; color: #666666; }
.btn-manage-delete {
  margin: 0 0 0 auto;
  min-width: 160rpx; height: 72rpx; line-height: 72rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 26rpx; font-weight: 700;
  border: none; border-radius: 36rpx; padding: 0 44rpx;
  text-align: center;
}
.btn-manage-delete::after { border: none; }
.ctb-price { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 2rpx; margin-right: 16rpx; }
.ctb-price-line { display: flex; align-items: baseline; }
.ctb-price-num { font-size: 36rpx; font-weight: 700; color: #E02020; line-height: 1; }
.ctb-saving { font-size: 20rpx; color: #999999; }
.btn-pay-popup {
  flex-shrink: 0; margin: 0;
  min-width: 200rpx; height: 76rpx; line-height: 76rpx;
  background-color: #E02E24; color: #FFFFFF;
  font-size: 26rpx; font-weight: 700;
  border: none; border-radius: 38rpx; padding: 0 36rpx;
  text-align: center;
}
/* 空车时支付按钮置灰禁用（点击仍走 payFromCart 的 toast 提示） */
.btn-pay-popup.btn-pay-disabled { background-color: #CCCCCC; }

/* ==================== 12. 修改提货人弹窗 ==================== */
.person-popup {
  width: 590rpx;
  background-color: #FFFFFF; border-radius: 24rpx;
  padding: 40rpx 32rpx 32rpx;
  position: relative;
}
.person-close { position: absolute; top: 20rpx; right: 24rpx; font-size: 30rpx; color: #CCCCCC; padding: 8rpx; }
.person-title { display: block; text-align: center; font-size: 30rpx; font-weight: 700; color: #333333; margin-bottom: 28rpx; }
/* 弹窗底部按钮组：清空（次级）+ 保存（主级）并排 */
.person-btns { display: flex; gap: 20rpx; margin-top: 8rpx; }
.btn-clear-person {
  flex: 1; height: 80rpx; line-height: 80rpx;
  margin: 0; padding: 0;
  background-color: #FFFFFF; color: #666666;
  font-size: 28rpx; font-weight: 700;
  border: 1rpx solid #DDDDDD; border-radius: 44rpx;
}
.btn-save-person {
  flex: 1; height: 80rpx; line-height: 80rpx;
  margin: 0; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 28rpx; font-weight: 700;
  border: none; border-radius: 44rpx;
}

/* ==================== 表单（原有流程 + 提货人弹窗共用） ==================== */
.form-group { margin-bottom: 24rpx; }
.form-label { display: block; font-size: 24rpx; font-weight: 600; color: #666666; margin-bottom: 12rpx; }
.form-input {
  width: 100%; height: 80rpx; line-height: 80rpx;
  background-color: #F5F5F5; border-radius: 12rpx; padding: 0 20rpx;
  font-size: 28rpx; color: #333333; box-sizing: border-box;
}
.btn-confirm {
  width: 100%; height: 80rpx; line-height: 80rpx;
  margin-top: 16rpx; padding: 0;
  background-color: #E02E24; color: #FFFFFF;
  font-size: 28rpx; font-weight: 700;
  border: none; border-radius: 0;
}
/* 提交飞行中禁用态：置灰提示"提交中…"，防重复下单（T-M1-09 验收②） */
.btn-confirm-disabled { background-color: #F5B5B0; color: rgba(255,255,255,.9); }

/* ==================== 支付弹窗（原有流程） ==================== */
.pay-section { margin-bottom: 24rpx; }
.section-title { display: block; font-size: 24rpx; font-weight: 700; color: #999999; margin-bottom: 14rpx; }
.pay-item { display: flex; align-items: center; padding: 10rpx 0; }
.pay-item-name { flex: 1; font-size: 26rpx; color: #333333; }
.pay-item-qty { font-size: 22rpx; color: #999999; margin: 0 20rpx; }
.pay-item-price { font-size: 26rpx; color: #E02020; font-weight: 600; }
.pay-info { display: block; font-size: 24rpx; color: #666666; margin-bottom: 8rpx; }
.pay-total { display: flex; justify-content: space-between; align-items: baseline; padding: 20rpx 0; border-top: 1rpx solid #F0F0F0; }
.pay-total text:first-child { font-size: 24rpx; color: #666666; }
.pay-total-price { font-size: 44rpx; font-weight: 700; color: #E02020; }
/* M4-T-02：合计右侧组（已抵扣提示 + 抵扣后金额） */
.pay-total-right { display: flex; align-items: baseline; gap: 14rpx; }
.pay-deducted { font-size: 22rpx; color: #E02020; }
/* M4-T-02：确认订单优惠券选择行（满足门槛可选，不满足置灰） */
.coupon-empty { padding: 4rpx 0 8rpx; }
.coupon-empty text { font-size: 24rpx; color: #999999; }
.coupon-option { display: flex; align-items: center; gap: 14rpx; padding: 14rpx 0; }
.coupon-radio {
  width: 34rpx; height: 34rpx; box-sizing: border-box; flex-shrink: 0;
  border: 2rpx solid #DDDDDD; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.coupon-radio text { font-size: 20rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
.coupon-option-active .coupon-radio { border: none; background-color: #E02020; }
.coupon-option-text { font-size: 26rpx; color: #333333; font-weight: 600; }
.coupon-option-state { margin-left: auto; font-size: 22rpx; color: #E02020; }
.coupon-option-disabled .coupon-option-text,
.coupon-option-disabled .coupon-option-state { color: #BBBBBB; }
/* M4-T-01：自提门店停业红色警示（点击跳自提点页切换） */
.pay-closed-warn { display: block; font-size: 24rpx; color: #E02020; font-weight: 600; margin: 4rpx 0 8rpx; }
/* M4-T-02：确认订单弹窗内容区内部滚动（清单+券行内容增多时不超面板 70vh） */
.popup-body-scroll { max-height: calc(70vh - 110rpx); overflow-y: auto; box-sizing: border-box; }

/* ==================== 确认订单弹窗未登录兜底引导（防御性：正常被游客拦截不可达） ==================== */
/* 轻量引导条：灰字提示 + 白底红字描边"去登录"小按钮（覆盖 uniapp button 默认样式含 ::after） */
.inline-login-guide { margin-top: 16rpx; display: flex; align-items: center; gap: 16rpx; }
.ilg-tip { flex: 1; min-width: 0; font-size: 22rpx; color: #999999; }
.ilg-btn {
  flex-shrink: 0; margin: 0;
  height: 64rpx; line-height: 64rpx;
  font-size: 24rpx; font-weight: 600; color: #E02020;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 12rpx;
  padding: 0 26rpx;
}
.ilg-btn::after { border: none; }

/* ==================== 模拟收银台弹窗（M2-T-05）：居中卡片，压过确认订单弹窗层级 ==================== */
.cashier-mask { z-index: 400; align-items: center; }
.cashier-panel { width: 590rpx; background-color: #FFFFFF; border-radius: 24rpx; overflow: hidden; }
.cashier-amount-row { display: flex; flex-direction: column; align-items: center; padding: 8rpx 0 20rpx; }
.cashier-amount-label { font-size: 24rpx; color: #999999; margin-bottom: 10rpx; }
.cashier-amount-line { display: flex; align-items: baseline; }
.cashier-symbol { font-size: 30rpx; font-weight: 700; color: #E02020; }
.cashier-amount { font-size: 64rpx; font-weight: 700; color: #E02020; line-height: 1; }
.cashier-order { display: block; text-align: center; font-size: 22rpx; color: #999999; margin-bottom: 12rpx; word-break: break-all; }
.cashier-tip { display: block; text-align: center; font-size: 20rpx; color: #CCCCCC; margin-bottom: 8rpx; }
</style>
