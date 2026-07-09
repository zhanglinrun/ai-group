package com.aigroup.paymall.trigger.job.support;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Date;

/**
 * Shared alipay reconcile logic for the order jobs (C1).
 * <p>
 * NoPayNotifyOrderJob and TimeoutCloseOrderJob both need the same
 * "query alipay, recover the payment if the buyer already paid" step, so it
 * lives here once. TimeoutCloseOrderJob additionally closes the alipay-side
 * trade before the local close, so a buyer can never pay an order that the
 * mall already treats as closed.
 */
@Slf4j
@Component
public class AlipayOrderReconcileSupport {

    public static final String RESPONSE_CODE_SUCCESS = "10000";
    public static final String TRADE_STATUS_SUCCESS = "TRADE_SUCCESS";
    /** the buyer never opened the cashier, no trade exists, nothing can be paid */
    public static final String SUB_CODE_TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";

    @Value("${alipay.enabled:true}")
    private boolean alipayEnabled;

    @Resource
    private AlipayClient alipayClient;
    @Resource
    private IOrderService orderService;

    /**
     * Queries alipay for the real trade status. When the trade is already paid
     * (code 10000 + TRADE_SUCCESS) the local order is advanced to PAY_SUCCESS
     * through the same recovery path as the pay callback, and true is returned.
     * <p>
     * Throws on query failure: callers must NOT close the order in that case,
     * because the payment state is unknown.
     */
    public boolean recoverIfPaidOnAlipay(String orderId) throws AlipayApiException {
        if (!alipayEnabled) return false;

        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel bizModel = new AlipayTradeQueryModel();
        bizModel.setOutTradeNo(orderId);
        request.setBizModel(bizModel);

        AlipayTradeQueryResponse response = alipayClient.execute(request);
        if (RESPONSE_CODE_SUCCESS.equals(response.getCode()) && TRADE_STATUS_SUCCESS.equals(response.getTradeStatus())) {
            Date sendPayDate = response.getSendPayDate();
            orderService.changeOrderPaySuccess(orderId, null != sendPayDate ? sendPayDate : new Date());
            return true;
        }
        return false;
    }

    /**
     * Closes the alipay-side trade so the buyer can no longer pay a timed-out
     * order. Returns true when the local close may proceed: alipay confirmed
     * the close, or the trade never existed. Returns false on any unconfirmed
     * state (e.g. the buyer paid concurrently, network failure); the caller
     * must skip the local close and let the next scheduled run reconcile again.
     */
    public boolean closeAlipayTrade(String orderId) {
        if (!alipayEnabled) return true;

        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel bizModel = new AlipayTradeCloseModel();
            bizModel.setOutTradeNo(orderId);
            request.setBizModel(bizModel);

            AlipayTradeCloseResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                return true;
            }
            if (SUB_CODE_TRADE_NOT_EXIST.equals(response.getSubCode())) {
                return true;
            }
            log.warn("alipay trade close not confirmed orderId:{} code:{} subCode:{}",
                    orderId, response.getCode(), response.getSubCode());
            return false;
        } catch (Exception e) {
            log.warn("alipay trade close failed orderId:{}", orderId, e);
            return false;
        }
    }

}
