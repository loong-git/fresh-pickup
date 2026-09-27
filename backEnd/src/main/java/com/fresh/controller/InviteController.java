package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.InviteCashVO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.InviteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 邀请新人接口（F-01.2 / F-04.2）：/api/invite/** 需登录（AuthInterceptor 权限矩阵）。
 * 邀请链接只含邀请码（inviteUrl 为相对路径，前端补 origin），记录手机号脱敏，无手机号枚举面（F-01.0）。
 * 现金接口金额一律 BigDecimal；提现文案/流水 remark 不透出内部词（F-04.2 修订④），
 * 演示环境模拟到账的明示由前端页面承担（F-04.3/F-04.5），prod 提现入口置灰同为前端职责。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/invite")
public class InviteController {

    @Autowired
    private InviteService inviteService;

    /**
     * 我的邀请主页（契约 F-01.2）：data = {inviteCode, inviteUrl, invitedCount, completedCount,
     * records:[{phoneMasked, statusText, createdAt, rewardedAt}]}。
     * 首次调用惰性生成 invite_code 并写回（复测幂等，返回同一码）。
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(inviteService.inviteInfo(userId));
    }

    /**
     * 邀请赚现金主页（契约 F-04.2 GET /api/invite/cash）：
     * data = {balance, frozenTotal, totalEarned, canWithdraw, withdrawMin:20,
     * flows:[{type,status,amount,balanceAfter,inviteeId,remark,createdAt}]}（流水倒序上限 50）。
     * 注册只冻结不入账（F-04.2 修订②）：balance 仅含已入账现金，冻结部分 frozenTotal 单列。
     */
    @GetMapping("/cash")
    public R<InviteCashVO> cash(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(inviteService.cashInfo(userId));
    }

    /**
     * 提现（契约 F-04.2 POST /api/invite/cash/withdraw）：定稿口径=满 20 提现全部余额（F-04.2 修订③），
     * 余额不足 → code:400「还差 X 元可提现」；并发双提由条件 UPDATE 防住，第二笔 code:400 引导重试。
     * data = {amount: 本次提现金额}。限流 1 次/分钟/用户（@RateLimit 默认 USER 维度），
     * 演示环境为模拟到账：明示在前端按钮/弹窗文案，本接口文案不出现内部词（F-04.2 修订④/F-04.5）；
     * wx 商家转账 TODO 见 InviteServiceImpl#withdraw。
     */
    @RateLimit(limit = 1, windowSeconds = 60)
    @PostMapping("/cash/withdraw")
    public R<Map<String, Object>> withdraw(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        BigDecimal amount = inviteService.withdraw(userId);
        return R.ok(Map.of("amount", amount), "提现申请成功");
    }
}
