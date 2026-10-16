package com.fresh.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.PasswordUtil;
import com.fresh.common.R;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminAuthService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 运营口令登录实现（F-20/G-06 Step 2，本期安全等级最高的一步；逐条对位契约 F-20.6 第 2 条）。
 *
 * <p><b>本类只做四件事</b>：① 成对键防枚举闸门；② 形状校验；③ 取行 → {@code PasswordUtil.verify} →
 * {@code status=active} 三重判定；④ 成功才签发 + 两个旁路写（last_login_at、admin_audit_log）。
 *
 * <p><b>防枚举（D-5）</b>：「无此账号」「口令不符」「已停用」「锁定中」四种失败<b>收敛为同一枚 401
 * 同一句「用户名或密码错误」</b>——失败原因只进服务端日志，绝不进 message、绝不分支文案；
 * 口令/哈希/token 一律不出现在任何对外字符串里（RD）。
 *
 * <p><b>出参纪律（G4/Step 1 已登记的继承面）</b>：本类拿到的 {@link AdminUser} 只读不用（不回实体）——
 * {@code BaseMapper.selectById} 一类通用方法生成的列清单也含 {@code password_hash}，所以物理闸门是
 * 实体上的 {@code @JsonIgnore} + 「出参一律重建白名单 Map」这条纪律，见 {@link #successData}。
 *
 * <p><b>不开事务</b>：last_login_at 与审计都是登录的<b>旁路</b>（{@code AdminUserMapper#touchLastLogin} 的 javadoc
 * 与本类 {@link #writeLoginAudit} 的双层 try-catch 同一口径）——已经签出的 token 不因旁路失败而作废，
 * 所以这里刻意没有 {@code @Transactional}（包起来反而会让旁路异常回滚掉业务本身，方向反了）。
 */
@Slf4j
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    /**
     * 失败三分支（+ 锁定分支）共用的唯一文案（D-5 防枚举）。
     * <p>刻意与 {@code AuthServiceImpl#loginPassword} 的「手机号或密码错误」不同句：本句是新增文案，
     * 不动 RE 红线那句「管理密钥缺失或错误」（两句各自服务于各自的闸门）。
     */
    private static final String LOGIN_FAIL_MSG = "用户名或密码错误";

    /** 账号可用态（m19/init 的 status 两态之一；disabled 与任何其它值一样按「不可登录」处理，不给第三态出口） */
    private static final String STATUS_ACTIVE = "active";

    /** username 列宽（admin_user.username VARCHAR(50)，m19 ①）：超过它必不可能命中，形状挡掉，<b>不单独成句</b> */
    private static final int MAX_USERNAME_LEN = 50;

    /**
     * 口令入参上限（形状闸门）：PBKDF2 每次 120_000 轮迭代，不设上限等于给匿名端点送一个
     * 「拿超长字符串换 CPU 时间」的放大器（本仓 C 端口令上限 20，但运营口令由主会话一次性 SQL 造，
     * 长度不由本仓 UI 约束，故这里取宽松的 128 只做 DoS 兜底，不做强度裁定）。
     */
    private static final int MAX_PASSWORD_LEN = 128;

    /**
     * username 入参形状闸门的字符类（F-20.29 BE-G6-3）：长度 1~{@link #MAX_USERNAME_LEN} 且
     * <b>不含任何控制字符与零宽字符</b>。
     * <p><b>为什么补这一枚</b>：旧闸门只判「空」与「&gt;50 字符」，实测 46 字符载荷
     * {@code x\nFORGED-A1D 运营登录成功: adminUserId=2, ip=1.2.3.4}（JSON 里合法转义的真换行）
     * 过得了闸门，随后被 {@code log.warn("运营登录失败: ip={}, username={}, reason={}", …, name, …)}
     * 原样打进服务端日志——换行让日志文件凭空多 2 行，其中一行读起来像真「运营登录成功」（日志行伪造）。
     * <p>字符类沿用 BE-G6-1 已立好的三枚（{@code AdminV2ServiceImpl#CATEGORY_PATTERN} 同款判例）：
     * {@code \p{Cntrl}}（ASCII 那 33 枚）+ {@code \p{Cc}}（补 C1 段 U+0080~U+009F，含 NEL U+0085）
     * + {@code \p{Cf}}（零宽一类 U+200B/200D/FEFF）——上一批实测的判例：<b>只写 {@code \p{Cntrl}}
     * 或 {@code \P{Cntrl}} 会放过零宽</b>，故必须三枚并列。
     * <p>锚点用 {@code \A…\z} 而非 {@code ^…$}：后者在 Java 里 {@code $} 可落在末尾换行之前，
     * {@code \z} 是绝对的输入末尾——即便有人日后把 {@code matches()} 换成 {@code find()}，末尾换行也进不来。
     * <p><b>不合形状后的语义一字不变（D-5 防枚举）</b>：同一句 401 {@link #LOGIN_FAIL_MSG}、同一枚
     * {@code R.CODE_UNAUTHORIZED}、<b>不计入</b>失败锁定、不查库不验密（见下面「2) 形状校验」原注释，
     * 该分支本就快，不为等时延改动它）。
     */
    private static final Pattern USERNAME_SHAPE_PATTERN =
            Pattern.compile("\\A[^\\p{Cntrl}\\p{Cc}\\p{Cf}]{1," + MAX_USERNAME_LEN + "}\\z");

    /**
     * 等量功占位哈希（BE-20-2 防枚举残余）：账号不存在时也对它跑一次 {@code PasswordUtil.verify}，
     * 让 no_such_account 分支与 bad_password 分支吃下同价 PBKDF2 120_000 轮（约百毫秒）的时延，
     * 抹平「响应快慢 = 用户名是否存在」这个可远程测量的 oracle。
     * <p>形状是合法 {@code base64(salt):base64(hash)}（两串各为固定随机字节的 Base64，保证 verify
     * 真走进派生计算而不是在格式校验处提前返回——否则白做）；值与任何真实口令<b>无关</b>，
     * 匹配概率按密码学等于 0，结果只用于抹平时延、除进 passwordOk 外无任何其它用途。
     */
    private static final String DUMMY_HASH = "0W8qvgR3w55RqC3+GbZgNw==:S54Szac48WC1JIjaXA5zr5Em/UDnWBuibMQJ7XWwLmE=";

    // ---------------------------------------------------------------
    // 防枚举锁（形态逐字照 PartnerOrderServiceImpl:82-114 的 D-14 核销锁，两处刻意的差异见下）
    // ---------------------------------------------------------------

    /** 触发上锁的连续失败次数阈值（D-5：<b>5</b> 次） */
    private static final int LOGIN_FAIL_LOCK_THRESHOLD = 5;

    /**
     * 失败计数窗口 = 锁定期时长（D-5：<b>10 分钟</b>）。
     * <p>计数窗口与锁定期共用这一个常量（核销锁同姿势），改一处两生效。
     */
    private static final Duration LOGIN_FAIL_LOCK_TTL = Duration.ofMinutes(10);

    /**
     * 失败计数：key=<b>{@code clientIp + "|" + username} 成对键</b>；expireAfterWrite=TTL，每次写入刷新窗口。
     * <p>【为什么必须是成对键而不是单独 username】核销锁的 key 是 userId（登录态已经服务端验过身份，
     * 锁的就是「这个本人」）；本端点是<b>匿名</b>的，username 是攻击方随便填的字符串——只按 username 计数的话，
     * 任何人拿别人的用户名连错 5 次就能把真运营踢下线（拒绝服务当武器）。加上 IP 半边后，
     * 攻击方要锁死某用户名必须先占住那个来源 IP，代价与收益不对称（D-5 逐字钉死成对键的理由）。
     * <p>【BE-20-3 已收口】admin_user 走 {@code utf8mb4_unicode_ci}（m19:42），{@code Admin}/{@code ADMIN}
     * 查的是<b>同一行</b>——旧实现里键却按原样用户名拼，成了两个键，跨大小写轮试可摊薄计数；
     * 现在键里的用户名统一 {@code trim().toLowerCase(Locale.ROOT)} 后再拼（见 login 方法内 pairKey 构造），
     * 同账号所有大小写写法落进同一把锁；查库仍用原值，大小写由 CI 排序规则自己处理。
     */
    private final Cache<String, Integer> loginFailCounts = Caffeine.newBuilder()
            .expireAfterWrite(LOGIN_FAIL_LOCK_TTL)
            .maximumSize(100_000)
            .build();

    /**
     * 锁定期：key 同上（成对键），命中即拒（<b>含正确口令</b>）；value=上锁时刻 epoch millis（BE-5 形态），
     * 401 信封的 {@code data.retryAfterSeconds} 由它 + TTL 推算剩余时长。
     */
    private final Cache<String, Long> loginLockouts = Caffeine.newBuilder()
            .expireAfterWrite(LOGIN_FAIL_LOCK_TTL)
            .maximumSize(100_000)
            .build();

    /** 兜底常量（照核销锁 LOCK_RETRY_FALLBACK_SECONDS）：剩余 TTL 取不到（读取与计算之间的竞态）时按锁的全长 600s 回 */
    private static final long LOCK_RETRY_FALLBACK_SECONDS = 600L;

    // ---------------------------------------------------------------
    // 审计旁路常量（admin_audit_log.detail VARCHAR(500)，列宽与兜底串照 OpsLogWriter 那两枚常量 / AdminServiceImpl 的 AUDIT_DETAIL_MAX 与 AUDIT_MINIMAL_DETAIL）
    // ---------------------------------------------------------------

    private static final int AUDIT_DETAIL_MAX = 500;

    /** 兜底 detail：「审计行必须存在」优先于「审计行有内容」（BE-2 硬要求）；本身合法 JSON 且不含任何凭据字样 */
    private static final String AUDIT_MINIMAL_DETAIL = "{\"note\":\"audit detail truncated\"}";

    /** 审计 detail 的 action / target_type 固定值（D-5 逐字：{@code admin.login} / {@code admin_user}） */
    private static final String AUDIT_ACTION_LOGIN = "admin.login";
    private static final String AUDIT_TARGET_TYPE_ADMIN_USER = "admin_user";

    /**
     * detail 序列化交 Jackson 全量转义（S-4 的教训：手拼 replace 挡不住控制码，写出的审计行 JSON_VALID=0）。
     * 本类 detail 只有一个短字符串 username（BE-G6-5 后 ip 键已删，见 {@link #buildLoginAuditDetail}），
     * 但仍走同一台 mapper——不给「手写转义」留第二次机会。
     */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;
    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 是否信任反代头 X-Forwarded-For——<b>读的是 RateLimitInterceptor 已有的那个键</b>
     * （{@code rate-limit.trust-proxy}，默认 false），本期不新增配置键、不改任何 yml（RG）。
     */
    @Value("${rate-limit.trust-proxy:false}")
    private boolean trustProxy;

    /**
     * 登录（契约 F-20.6 第 2 条的六步，顺序即下面的注释序号）。
     *
     * <p>1) 锁定闸门 → 2) 形状校验 → 3) 取行 + 验密 + 判 status（三失败收一句） → 4) 清零计数
     * → 5) 签发 token → 6) 两个旁路写（last_login_at、admin_audit_log）→ 回白名单 Map。
     */
    @Override
    public Map<String, Object> login(String username, String password, HttpServletRequest request) {
        String clientIp = clientIp(request);
        String name = username == null ? "" : username.trim();
        String rawPassword = password == null ? "" : password;
        // BE-20-3：成对键里的用户名先归一化（trim + 小写）再拼——admin_user 走 utf8mb4_unicode_ci，
        // Admin/ADMIN 命中<b>同一行</b>却是旧实现的<b>两个键</b>，跨大小写轮试可摊薄 5 次锁定计数；
        // 归一化让同账号的所有大小写写法落进同一把锁。<b>查库仍用原值 name</b>（CI 排序规则自己处理）。
        String pairKey = clientIp + "|" + name.toLowerCase(Locale.ROOT);

        // 1) 锁定闸门排在最前（核销锁 step 1 同姿势）：锁定期内连<b>正确口令</b>也拒——
        //    这才是「防在线爆破」的意义；回的还是同一句 401，只在 data 里多一个 retryAfterSeconds（D-5/BE-5）。
        Long lockedAt = loginLockouts.getIfPresent(pairKey);
        if (lockedAt != null) {
            throw lockedFailure(lockedAt, name);
        }

        // 2) 形状校验（F-20.6 第 2 条末项 + F-20.29 BE-G6-3）：空/超长/<b>含控制字符或零宽</b>一律同一句 401，
        //    <b>不</b>回「参数格式不正确」——后者会与「用户名不存在」区分得开，等于把 D-5 的防枚举目标从侧面捅穿。
        //    挡控制字符/零宽的理由见 {@link #USERNAME_SHAPE_PATTERN}：不过滤的话它们会随 3) 失败分支的
        //    log.warn(username={}) 原样进日志文件，换行即伪造日志行（实测 FORGED-A1D 载荷凭空多出 2 行）。
        //    形状不合<b>不计入</b>失败次数（照核销锁 step 2 的取舍：口误/空提交不该记账，见其 :221-225 注释）；
        //    匿名爆破的真正频次上限由端点上的 @RateLimit(5 次/分/IP) 兜住，两层各司其职。
        if (!USERNAME_SHAPE_PATTERN.matcher(name).matches()
                || rawPassword.isEmpty() || rawPassword.length() > MAX_PASSWORD_LEN) {
            log.warn("运营登录入参形状不合（不计数、不查库）: ip={}, nameLen={}, pwdLen={}",
                    clientIp, name.length(), rawPassword.length());
            throw new BizException(R.CODE_UNAUTHORIZED, LOGIN_FAIL_MSG);
        }

        // 3) 唯一取行通道 = selectByUsername（D-5：它是全仓唯一显式读 password_hash 的一条手写 SQL）；
        //    三个布尔分开算只为让<b>服务端日志</b>能定性（下面 warn 的 reason），对外一律同一句、同一枚码。
        //    BE-20-2：verify 无条件执行——账号不存在时改对 DUMMY_HASH 跑（等量功、必然不匹配、结果丢弃），
        //    不再用 exists && 短路跳过 PBKDF2，堵死「no_such_account 响应快约百毫秒」的时延枚举面。
        AdminUser account = adminUserMapper.selectByUsername(name);
        boolean exists = account != null;
        boolean passwordOk = PasswordUtil.verify(rawPassword, exists ? account.getPasswordHash() : DUMMY_HASH);
        boolean active = exists && STATUS_ACTIVE.equals(account.getStatus());
        if (!exists || !passwordOk || !active) {
            recordFailure(pairKey);
            // username={} 打的是过闸后的归一值 name（已经 USERNAME_SHAPE_PATTERN 挡掉控制字符/零宽，
            // 且长度 ≤50，双保险）——绝不回到请求原值或库里取出的串，日志行伪造没有第二次机会。
            log.warn("运营登录失败: ip={}, username={}, reason={}", clientIp, name,
                    exists ? (passwordOk ? "account_disabled" : "bad_password") : "no_such_account");
            throw new BizException(R.CODE_UNAUTHORIZED, LOGIN_FAIL_MSG);
        }

        // 4) 成功即清零：失败计数与锁定态一并作废（clearVerifyState 同姿势）
        loginFailCounts.invalidate(pairKey);
        loginLockouts.invalidate(pairKey);

        // 5) 签发 typ=admin 运营 JWT（subject=admin_user.id；D-6 的载荷口径全在 JwtUtil#createAdminToken 里）
        String token = jwtUtil.createAdminToken(account.getId(), account.getUsername());

        // 6) 两个旁路写：先 last_login_at（0 行只 warn），再审计（双层 try-catch 吞异常）；
        //    任一步失败都<b>不打断已签出的 token</b>——token 是本次登录的唯一产物。
        touchLastLoginSafely(account.getId());
        writeLoginAudit(account.getId(), account.getUsername(), clientIp);
        // 成功日志的 username={} 同样用过闸后归一值 name（BE-G6-3 双保险）：account.getUsername() 是库里的串，
        // 理论上是唯一可能藏着异常字符的来源，不再直接进日志（审计行仍写库值——那是 Jackson 全量转义的 DB 落盘，不是日志）。
        log.info("运营登录成功: adminUserId={}, username={}, ip={}", account.getId(), name, clientIp);

        return successData(token, account);
    }

    // ---------------------------------------------------------------
    // 支撑方法
    // ---------------------------------------------------------------

    /**
     * 达阈值即上锁（照 {@code PartnerOrderServiceImpl#recordVerifyFailure :324-335}，键换成成对键）：
     * 计数 +1，≥{@link #LOGIN_FAIL_LOCK_THRESHOLD} 写入上锁时刻并清计数（解锁后从 0 开始重新计）。
     */
    private void recordFailure(String pairKey) {
        int fails = loginFailCounts.asMap().compute(pairKey, (key, current) -> current == null ? 1 : current + 1);
        if (fails >= LOGIN_FAIL_LOCK_THRESHOLD) {
            loginLockouts.put(pairKey, System.currentTimeMillis());
            loginFailCounts.invalidate(pairKey);
            log.warn("运营登录防枚举上锁: fails={}, lockFor={}", fails, LOGIN_FAIL_LOCK_TTL);
        }
    }

    /**
     * 锁定期的 401：文案仍是那一句（<b>不给「已锁定」这种可区分信号</b>，D-5），
     * 只按 BE-5 已有的 data 透传通道（{@code BizException} 三参构造器 + {@code GlobalExceptionHandler#handleBiz}）
     * 如实回剩余秒数，省得合法运营对着同一句 401 无限重试。
     */
    private BizException lockedFailure(Long lockedAt, String name) {
        long remainingMs = LOGIN_FAIL_LOCK_TTL.toMillis() - (System.currentTimeMillis() - lockedAt);
        long retryAfterSeconds = remainingMs > 0
                ? (long) Math.ceil(remainingMs / 1000.0)
                : LOCK_RETRY_FALLBACK_SECONDS;
        log.warn("运营登录锁定期内被拒: username={}, 剩余锁定时长={}s", name, retryAfterSeconds);
        return new BizException(R.CODE_UNAUTHORIZED, LOGIN_FAIL_MSG,
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    /**
     * 出参<b>白名单</b>（G4/D-5 逐字：{@code {token, displayName, adminUserId}}）：三行手挑，
     * 实体一个字段都不透传——所以 {@code passwordHash} 连「被 @JsonIgnore 挡一次」的机会都不给它，
     * {@code status}/{@code lastLoginAt}/{@code createTime} 这些也没出口（本期出参就这三键，门禁 G1 按键名清单核对）。
     * <p>displayName 为 null/空白 → 回退 username（D-5「为空时回 username」）。
     * Map.of 不容 null，故用 LinkedHashMap（照 AuthServiceImpl:140-149 的同类处理）。
     */
    private Map<String, Object> successData(String token, AdminUser account) {
        String displayName = account.getDisplayName();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("displayName", displayName == null || displayName.isBlank() ? account.getUsername() : displayName);
        data.put("adminUserId", account.getId());
        return data;
    }

    /**
     * last_login_at 是旁路（{@code AdminUserMapper#touchLastLogin} javadoc 的定性）：
     * 返回 0 行（id 已被删）只 warn，<b>绝不因此打断已签出的 token</b>；DB 异常同理吞掉——
     * 令牌已经在返回值里，此刻把一个「已登录成功」的人打成 500 才是真故障。
     */
    private void touchLastLoginSafely(Long adminUserId) {
        try {
            int updated = adminUserMapper.touchLastLogin(adminUserId, LocalDateTime.now());
            if (updated == 0) {
                log.warn("运营登录 last_login_at 未落库（0 行，旁路不打断登录）: adminUserId={}", adminUserId);
            }
        } catch (Exception e) {
            log.warn("运营登录 last_login_at 写入异常（旁路，不影响已签出的 token）: adminUserId={}", adminUserId, e);
        }
    }

    /**
     * 登录审计（D-5/D-8：Admin 侧动作写 {@code admin_audit_log}，<b>不写 ops_log</b>——BE-13 白名单不许放宽）：
     * actor={@code admin_user:<id>}、action={@code admin.login}、target_type={@code admin_user}、
     * target_id={@code String.valueOf(id)}、ip=客户端 IP、detail=只有 username（BE-G6-5：ip 由列承载，不再进 detail）。
     *
     * <p>写失败<b>旁路吞掉</b>，形态逐字照 {@code OpsLogWriter#write :50-69}（即 AdminServiceImpl#writeAudit :392-408 的
     * 母版）：第一趟完整 detail，任一层原因（1406 超长、字符集、列宽…）失败即退成最小 detail 重试一次，
     * 两趟都失败才 warn——审计凭空消失是不可发现的脱审，所以「失败也要再落一行」优先。
     */
    private void writeLoginAudit(Long adminUserId, String username, String clientIp) {
        String actor = "admin_user:" + adminUserId;
        String targetId = String.valueOf(adminUserId);
        String detail = buildLoginAuditDetail(username);
        try {
            insertAudit(actor, targetId, detail, clientIp);
            return;
        } catch (Exception e) {
            log.warn("运营登录审计写入失败（旁路，不影响主流程），改用最小 detail 重试: adminUserId={}", adminUserId, e);
        }
        try {
            insertAudit(actor, targetId, AUDIT_MINIMAL_DETAIL, clientIp);
        } catch (Exception e) {
            log.warn("运营登录审计重试仍失败（旁路，不影响主流程）: adminUserId={}", adminUserId, e);
        }
    }

    /**
     * detail 组装：只有 {@code {"username":...}} 一键（F-20.29 BE-G6-5，D-5 的「绝不含口令、哈希、token」不变），
     * <b>ip 键已删</b>——同一枚客户端 IP 本来就写在 {@code admin_audit_log.ip} <b>列</b>里
     * （实测 13 行 detail 里的 ip 与 ip 列逐字同值），detail 再带一份是零信息增量的冗余，
     * 还会把「v2 审计面 {@code ip} 连 SELECT 都没有」这条最小暴露断言被写入端自己抵消。
     * 本方法连口令/哈希/token 都拿不到：入参只有用户名——想泄漏也没有通道。
     * 先序列化再按列宽兜底，产出恒为合法 JSON 且 ≤ VARCHAR(500)。
     */
    private String buildLoginAuditDetail(String username) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("username", username);
        try {
            String json = AUDIT_MAPPER.writeValueAsString(detail);
            return json.length() <= AUDIT_DETAIL_MAX ? json : AUDIT_MINIMAL_DETAIL;
        } catch (JsonProcessingException e) {
            // String 值实际序列化不可失败；兜底仍是合法 JSON，绝不因为序列化问题把登录审计整行丢掉
            return AUDIT_MINIMAL_DETAIL;
        }
    }

    /** createdAt 不填：admin_audit_log.created_at 由 DB {@code DEFAULT CURRENT_TIMESTAMP} 落值（照 AdminServiceImpl#insertAudit 同姿势） */
    private void insertAudit(String actor, String targetId, String detail, String clientIp) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActor(actor);
        row.setAction(AUDIT_ACTION_LOGIN);
        row.setTargetType(AUDIT_TARGET_TYPE_ADMIN_USER);
        row.setTargetId(targetId);
        row.setDetail(detail);
        row.setIp(clientIp);
        adminAuditLogMapper.insert(row);
    }

    /**
     * 客户端 IP —— <b>逐条镜像 {@code interceptor/RateLimitInterceptor.java} 那条私有 {@code clientIp(...)}</b>：
     * 默认直取 {@code request.getRemoteAddr()}（TCP 对端地址，不可伪造）；仅
     * {@code rate-limit.trust-proxy=true}（显式声明部署在可信反代后）才解析 X-Forwarded-For 首跳。
     * 读同一个既有配置键、同一条判定顺序，不新增键、不改 yml（RG）。
     *
     * <p>【为什么必须镜像而不是「直接信 XFF」】成对键的 IP 半边若由请求方随意伪造，
     * 轮换 {@code X-Forwarded-For} 就能让每次失败落进不同键 → 5 次/10 分钟的防枚举锁完全失效
     * （RateLimitInterceptor:39-48 写的是同一件事，且已实测「无条件信任 XFF 首跳」可完全绕过）。
     * 审计行的 ip 列也取同一个值：两处口径一致，排障时才对得上。
     *
     * <p><b>【债登记】本仓第 2 份 clientIp 实现</b>（第 1 份=RateLimitInterceptor:101；
     * {@code AdminServiceImpl#currentIp :526-532} 是同族第 3 份，但它不解析 XFF、也不受 trust-proxy 控制），
     * 与 PhoneMask 同类债，抽公共工具随 G-07（本期不动那两处，越界改存量链路反而扩大回归面）。
     */
    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
