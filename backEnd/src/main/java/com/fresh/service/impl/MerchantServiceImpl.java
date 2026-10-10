package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantProfileUpdateDTO;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantProfile;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.service.MerchantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商家端服务实现（F-12/G-01 Step 4d）。
 * R3 铁律落地方式（两条硬约束，契约 §2.11 主会话 Step 3 实测后新增）：
 * 1) 每个以 merchantId 为参的方法第一行判空回 403——ATTR_MERCHANT_ID 由拦截器无条件注入，
 *    role=merchant 但 merchant_id 为 NULL 的行（m15 列可空）若落到 WHERE merchant_id = null 会静默空集，
 *    一旦被写成「参数为空即不加条件」的动态 SQL 就是全表泄露；403 与拦截器同口径，
 *    不区分「你不是商家」与「你没归属」，避免新增泄露面。
 * 2) 读写一律带归属条件（merchant 表 WHERE id=merchantId，merchant_profile WHERE merchant_id=merchantId），
 *    查无 → 404；绝不「先按 id 查再内存比归属」（那会把存在性差异写进时序或文案）。
 */
@Slf4j
@Service
public class MerchantServiceImpl implements MerchantService {

    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private MerchantProfileMapper merchantProfileMapper;

    @Override
    public Map<String, Object> getMe(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getId, merchantId));
        if (merchant == null) {
            // 404 口径同既有业务侧「<主体>不存在」（订单/菜品/评价不存在），两处缺失同文案：
            // 不区分「主体没了」还是「资质没了」，防存在性差异成为可枚举信号
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        MerchantProfile profile = merchantProfileMapper.selectOne(new LambdaQueryWrapper<MerchantProfile>()
                .eq(MerchantProfile::getMerchantId, merchantId));
        if (profile == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }

        Map<String, Object> me = new LinkedHashMap<>();
        // 角色判定入口（契约 §3.2「me 返回 role=merchant」）：拦截器已裁决，回常量不额外查库
        me.put("role", "merchant");
        me.put("merchantId", merchant.getId());
        me.put("name", merchant.getName());
        // R4：手机号一律脱敏（出参绝不直出 contactPhone 明文）
        me.put("contactPhone", maskPhone(merchant.getContactPhone()));
        me.put("contactName", merchant.getContactName());
        me.put("status", merchant.getStatus());
        Map<String, Object> profileMap = new LinkedHashMap<>();
        profileMap.put("auditStatus", profile.getAuditStatus());
        profileMap.put("businessLicense", profile.getBusinessLicense());
        profileMap.put("legalPerson", profile.getLegalPerson());
        profileMap.put("address", profile.getAddress());
        me.put("profile", profileMap);
        return me;
    }

    @Override
    @Transactional
    public void updateProfile(Long merchantId, MerchantProfileUpdateDTO dto) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        if (dto.getContactName() == null && dto.getAddress() == null
                && dto.getBusinessLicense() == null && dto.getLegalPerson() == null) {
            // 全空请求：口径对齐 AdminServiceImpl.updateDish 的部分更新判定（400 无更新字段），
            // 而非静默假成功——调用方需知道自己什么都没改
            throw new BizException("无更新字段（可传 contactName / address / businessLicense / legalPerson）");
        }

        // 写一：merchant.contact_name（联系人属主体列，merchant 表的归属条件即主键 = 服务端解析的 merchantId）
        if (dto.getContactName() != null) {
            int rows = merchantMapper.update(null, new LambdaUpdateWrapper<Merchant>()
                    .eq(Merchant::getId, merchantId)
                    .set(Merchant::getContactName, dto.getContactName()));
            if (rows == 0) {
                // 连接串未开 useAffectedRows → 返回 matched rows，同值更新也计 1，故 0 行只会是主体不存在
                throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
            }
        }

        // 写二：merchant_profile 三列（资质侧可编辑字段，WHERE merchant_id=? 强制归属）
        if (dto.getAddress() != null || dto.getBusinessLicense() != null || dto.getLegalPerson() != null) {
            LambdaUpdateWrapper<MerchantProfile> wrapper =
                    new LambdaUpdateWrapper<MerchantProfile>().eq(MerchantProfile::getMerchantId, merchantId);
            if (dto.getAddress() != null) {
                wrapper.set(MerchantProfile::getAddress, dto.getAddress());
            }
            if (dto.getBusinessLicense() != null) {
                wrapper.set(MerchantProfile::getBusinessLicense, dto.getBusinessLicense());
            }
            if (dto.getLegalPerson() != null) {
                wrapper.set(MerchantProfile::getLegalPerson, dto.getLegalPerson());
            }
            if (merchantProfileMapper.update(null, wrapper) == 0) {
                throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
            }
        }
        log.info("商家端更新资料: merchantId={}, contactName={}, address={}, businessLicense={}, legalPerson={}",
                merchantId, dto.getContactName() != null, dto.getAddress() != null,
                dto.getBusinessLicense() != null, dto.getLegalPerson() != null);
    }

    /** 138****1234（逐字复刻 OrderServiceImpl#maskPhone:347-349；merchant.contact_phone 由代开通
     *  DTO @Pattern("^1\\d{10}$") 与 m15 种子保证 11 位，substring 安全，同 FreeServiceImpl:449 口径注释） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
