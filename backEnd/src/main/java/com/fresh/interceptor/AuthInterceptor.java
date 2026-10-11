package com.fresh.interceptor;

import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.common.ResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 认证拦截器（T-M2-02，契约 M2-2/M2-3）：
 * - 解析 Authorization: Bearer &lt;token&gt; → userId 注入 request attribute（控制器 @RequestAttribute 读取）；
 * - 权限矩阵：需登录白名单之外一律匿名可读（游客模式浏览/加购不受影响）；
 * - 无/伪造/过期 token 访问需登录接口 → 统一信封 401（写 response JSON，不抛裸异常）；
 * - OPTIONS 预检直通（Spring 会为 preflight 保留用户拦截器链，必须显式放行）；
 * - 前缀判定的输入路径统一取「Spring 映射 handler 用的那条已解码路径」（BE-1，见 mappedPath），
 *   绝不用未解码的 request.getRequestURI()，否则 /api/%6derchant/** 之类的百分号编码可绕过全部鉴权。
 * 注册顺序：本拦截器在 RateLimitInterceptor 之前（用户维度限流依赖 userId attribute）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** request attribute key：当前登录用户ID */
    public static final String ATTR_USER_ID = "userId";
    /** request attribute key：当前商家ID（F-12/G-01，/api/merchant/** 分支注入；Controller @RequestAttribute 读取） */
    public static final String ATTR_MERCHANT_ID = "merchantId";

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private com.fresh.mapper.UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检直通
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long userId = resolveUserId(request);
        // BE-1（F-13.10 #1，critical）：本行以下**全部**前缀判定只用 path（Spring 映射 handler 时用的那条已解码路径），
        // 不再用 request.getRequestURI()。旧值给的是未解码 URI，而 Spring MVC 按解码后路径找 handler，
        // 于是 `/api/%6derchant/uploads`（%6d='m'）不命中 /api/merchant/ 分支却照样进 upload ——
        // 主会话实测无 token 真落盘；`/api/%75ser/avatar` 同法绕过 → 证明 /api/** 全系前缀判定都可被编码绕过。
        // 判定输入只留一个值（混合态等于洞还在）：requiresLogin(:56)、/api/merchant/ 分支(:70)、
        // requiresLogin 方法体内 :122-165 的每一个 startsWith/equals/matches 都吃同一个 path。
        String path = mappedPath(request);

        // F-12/G-01 双向 typ 隔离铁律：全部需登录分支解析 claims 后凡 typ=admin 一律拒
        // （G-06 签发 typ=admin 运营 JWT 后生效；G-01 阶段 C 端 token 无 typ claim，本检查 dormant）
        if (userId != null) {
            io.jsonwebtoken.Claims claims = resolveClaims(request);
            if (claims != null && "admin".equals(claims.get("typ"))) {
                ResponseUtil.writeJson(response, R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
                return false;
            }
        }

        if (!requiresLogin(request.getMethod(), path)) {
            if (userId != null) {
                request.setAttribute(ATTR_USER_ID, userId);
            }
            return true;
        }

        if (userId == null) {
            ResponseUtil.writeJson(response, R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
            return false;
        }
        request.setAttribute(ATTR_USER_ID, userId);

        // F-12/G-01 查库定角色：/api/merchant/** 前缀需 role=merchant 双校验（复刻 :121-123 /api/user/** 范式）
        if (path.startsWith("/api/merchant/")) {
            com.fresh.entity.User u = userMapper.selectById(userId);
            // merchant_id 为空必须在此收口 403：setAttribute(name, null) 按 Servlet 语义等于移除属性，Controller 的
            // @RequestAttribute(merchantId)（required 默认 true）会先抛 ServletRequestBindingException 被兜底转 500
            // → Service 层那两行判空 403 不可达（此判空勿删）
            if (u == null || !"merchant".equals(u.getRole()) || u.getMerchantId() == null) {
                ResponseUtil.writeJson(response, R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
                return false;
            }
            request.setAttribute(ATTR_MERCHANT_ID, u.getMerchantId());
        }
        return true;
    }

    /**
     * 鉴权判定用的路径（BE-1 / F-13.10 #1 critical）：必须与 Spring MVC 映射 handler 时用的那条路径同源。
     * <p>取 request.getServletPath()：Boot 内嵌 Tomcat 下 DispatcherServlet 挂在 "/"，Tomcat 在把请求交给
     * MVC 之前已对 URI 完成 URL 解码与归一（合并 //、剥 ./ 与 ../、去掉 ; 路径参数），servletPath 就是那条
     * 「已解码 + 已归一」的映射路径本身，与真实请求路径逐字相等 → 本类下方每一个 startsWith/equals/matches
     * （含 ^/api/dishes/\d+/reviews$、^/api/pay/[^/]+/wx-notify$ 这类依赖真实路径形态的正则）语义一字不变。
     * <p>【为什么不用 HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE】派发简报首选它，但它给的是
     * **映射模板**而不是路径：ReviewController:47 的模板是 /api/dishes/{dishId}/reviews，
     * 喂给 :171 的 ^/api/dishes/\\d+/reviews$ 不再命中（{dishId} 不是数字）→
     * POST 发评价从「需登录」退化成匿名可写，比本条要修的洞更严重，故弃用；已按简报第 6 项列「契约疑点」回报。
     * <p>servletPath 为空只出现在非 Boot 的标准 servlet 映射形态（DispatcherServlet 挂 /*.ext 时路径落在
     * pathInfo）；两者都取不到才退回 getRequestURI()，即与改动前一致的旧行为，不留 null。
     */
    private String mappedPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getPathInfo();
        }
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path;
    }

    /** 从 Authorization 头解析 token → userId；缺失/格式错/解析失败返回 null */
    private Long resolveUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        return jwtUtil.parseUserId(token);
    }

    /** 从 Authorization 头解析 token → 完整 Claims；缺失/格式错/解析失败返回 null（F-12/G-01 typ 隔离用） */
    private io.jsonwebtoken.Claims resolveClaims(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        if (token.isEmpty()) return null;
        return jwtUtil.parseClaims(token);
    }

    /**
     * 需要登录的白名单（契约 M2-3）：
     * POST /api/orders、POST /api/orders/{id}/cancel、GET /api/orders、
     * POST /api/dishes/{dishId}/reviews、POST /api/pay/**、/api/coupons**（T-M4-02 领券/我的券）、
     * GET /api/reviews/mine（M7 我的评价）、POST /api/auth/set-password（T-M5 登录态改密）、
     * POST /api/free/claim、GET /api/free/my-claims（M7 免费领商品：用户维度数据）；
     * GET /api/invite/**（F-01 /api/invite/me + F-04 /api/invite/cash、/api/invite/cash/withdraw：
     * 邀请码/统计/记录与现金余额/提现均用户维度数据，前缀整体登录）；
     * 例外豁免：POST /api/pay/{id}/wx-notify（需求 A，微信服务器回调无 token，匿名可达）；
     * 匿名可读：GET /api/dishes**（含 GET 评价列表）、GET /api/stores（T-M4-01）、
     * GET /api/seckill/current（T-M4-03）、POST /api/auth/**（T-M5 login-password 匿名，仅 set-password 例外）、
     * GET /api/free/current（M7 免费领商品，匿名可见今日商品，登录后附已领标记）、GET /api/dict。
     */
    private boolean requiresLogin(String method, String path) {
        // T-M5 密码登录：set-password 属登录态改密需登录（无/失效 token → 401）；
        // login-password 匿名可达（POST /api/auth/** 默认匿名，此处仅显式收窄 set-password）
        if (path.equals("/api/auth/set-password")) {
            return true;
        }
        if (path.startsWith("/api/pay/")) {
            // 微信支付回调端点豁免（需求 A）：微信服务器无 token，匿名可达；
            // 安全性由回调自身的平台证书验签保障（TODO 见 PayController#wxNotify）
            if (path.matches("^/api/pay/[^/]+/wx-notify$")) {
                return false;
            }
            return true;
        }
        // T-M4-02 领券/我的券均需登录（用户维度数据）
        if (path.startsWith("/api/coupons")) {
            return true;
        }
        if (path.equals("/api/orders")) {
            return true;
        }
        if (path.matches("^/api/orders/[^/]+/cancel$")) {
            return true;
        }
        // M7 我的评价：用户维度数据需登录；path 全等匹配，与 GET /api/dishes/{dishId}/reviews
        // 匿名列表规则（下方 ^/api/dishes/\d+/reviews$ 正则）路径前缀不同，互不命中、无顺序冲突
        if (path.equals("/api/reviews/mine")) {
            return true;
        }
        // M7 免费领商品：领取与我的领取记录为用户维度数据需登录；GET /api/free/current 保持匿名可读
        if (path.equals("/api/free/claim") || path.equals("/api/free/my-claims")) {
            return true;
        }
        // F-01 邀请新人：邀请主页（邀请码/统计/记录）为用户维度数据需登录；惰性发码在 service 内完成
        if (path.startsWith("/api/invite/")) {
            return true;
        }
        // F-06.7.4 R8 用户资料：GET /api/user/me、PUT /api/user/avatar 均用户维度数据，前缀整体登录
        if (path.startsWith("/api/user/")) {
            return true;
        }
        // F-12/G-01 商家端：/api/merchant/** 前缀整体登录（角色双校验在 preHandle 查库定角色，此处只判登录）
        if (path.startsWith("/api/merchant/")) {
            return true;
        }
        // 仅评价提交需登录；GET /api/dishes/{dishId}/reviews 列表保持匿名可读
        return "POST".equalsIgnoreCase(method) && path.matches("^/api/dishes/\\d+/reviews$");
    }
}
