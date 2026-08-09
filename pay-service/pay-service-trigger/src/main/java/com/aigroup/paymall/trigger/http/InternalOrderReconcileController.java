package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.api.dto.OrderReconcileResponseDTO;
import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.http.support.InternalCallbackAuthSupport;
import com.aigroup.paymall.trigger.job.support.AlipayOrderReconcileSupport;
import com.aigroup.paymall.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

/** Internal, signed reconciliation endpoint; never intended for browser traffic. */
@Slf4j
@RestController
@RequestMapping("/internal/pay/orders")
public class InternalOrderReconcileController {

    @Resource
    private InternalCallbackAuthSupport internalCallbackAuthSupport;

    @Resource
    private AlipayOrderReconcileSupport reconcileSupport;

    @Resource
    private IOrderService orderService;

    @PostMapping("/{orderId}/reconcile")
    public Response<OrderReconcileResponseDTO> reconcile(
            @PathVariable String orderId,
            HttpServletRequest servletRequest) {
        if (!internalCallbackAuthSupport.isAuthorized(servletRequest)) {
            return Response.<OrderReconcileResponseDTO>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("invalid internal credential")
                    .build();
        }
        OrderReconcileResponseDTO data = new OrderReconcileResponseDTO();
        data.setOrderId(orderId);
        try {
            OrderEntity before = orderService.queryOrderByOrderId(orderId);
            if (before == null) {
                return Response.<OrderReconcileResponseDTO>builder()
                        .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("order not found")
                        .build();
            }
            boolean recovered = reconcileSupport.recoverIfPaidOnAlipay(orderId);
            OrderEntity after = orderService.queryOrderByOrderId(orderId);
            data.setRecovered(recovered);
            data.setStatus(after == null || after.getOrderStatusVO() == null
                    ? null : after.getOrderStatusVO().getCode());
            return Response.<OrderReconcileResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.warn("internal payment reconciliation failed orderId={}", orderId, e);
            return Response.<OrderReconcileResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info("reconciliation unavailable")
                    .data(data)
                    .build();
        }
    }
}
