package com.aigroup.paymall.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 当面付/扫码支付下单应答：返回订单号与 qr_code（前端渲染成二维码，轮询 sync_settle 查结果）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreatePayQrResponseDTO {
    /** 订单号（= 支付宝 out_trade_no，前端用于轮询支付状态） */
    private String orderId;
    /** 支付宝 qr_code 串（前端渲染成二维码；为空表示暂不可用） */
    private String qrCode;
    /** 页面支付表单（当面付预下单暂不可用时，前端可回退到支付宝沙箱页面支付）。 */
    private String payUrl;
    /** 服务端订单快照计算出的展示应付金额，不能由浏览器根据营销试算自行推导。 */
    private BigDecimal amount;
    /** 仅 dev profile + 显式开关开启时为 true；生产环境始终为 false。 */
    private boolean demoCompletionEnabled;
}
