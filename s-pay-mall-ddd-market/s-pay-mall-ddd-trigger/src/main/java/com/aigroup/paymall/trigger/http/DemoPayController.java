package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.order.adapter.port.IDemoGroupPort;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import com.aigroup.paymall.types.common.Constants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * Explicit local demonstration payment entry point.
 *
 * <p>The bean only exists for the {@code dev} profile when the opt-in flag is true. Identity is
 * resolved from Gateway-injected headers, so the browser never receives the internal token.</p>
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(name = "ai-group.pay.demo-complete-enabled", havingValue = "true")
@RequestMapping("/api/v1/alipay")
public class DemoPayController {

    private final IOrderService orderService;
    private final IDemoGroupPort demoGroupPort;
    private final GatewayUserResolver gatewayUserResolver;

    public DemoPayController(IOrderService orderService,
                             IDemoGroupPort demoGroupPort,
                             GatewayUserResolver gatewayUserResolver) {
        this.orderService = orderService;
        this.demoGroupPort = demoGroupPort;
        this.gatewayUserResolver = gatewayUserResolver;
    }

    @PostMapping("/demo_complete")
    public Response<String> complete(@RequestParam String outTradeNo, HttpServletRequest request) {
        try {
            String userId = gatewayUserResolver.resolveUserId(request, null);
            OrderEntity order = orderService.queryOrderByOrderId(outTradeNo);
            if (order == null || !userId.equals(order.getUserId())) {
                return illegal("order not found or not owned");
            }

            OrderStatusVO status = order.getOrderStatusVO();
            boolean payable = OrderStatusVO.PAY_WAIT.equals(status) || OrderStatusVO.PAY_SUCCESS.equals(status);
            boolean settled = OrderStatusVO.MARKET.equals(status) || OrderStatusVO.DEAL_DONE.equals(status);
            if (!payable && !settled) {
                return illegal("only payable or settled orders can be completed in demo mode");
            }

            // Always retry the normal payment-success service while PAY_WAIT/PAY_SUCCESS. For a group order this
            // performs the real pay -> group settlement registration and is idempotent on a retry.
            if (payable) {
                orderService.changeOrderPaySuccess(outTradeNo, new Date());
            }

            if (MarketTypeVO.GROUP_BUY_MARKET.getCode().equals(order.getMarketType())) {
                if (!demoGroupPort.finalizePaidGroup(userId, outTradeNo)) {
                    return Response.<String>builder()
                            .code(Constants.ResponseCode.UN_ERROR.getCode())
                            .info("group settlement is not ready; retry shortly")
                            .build();
                }
                return success("GROUP_FINALIZED");
            }
            return success("SETTLED");
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info("demo payment completion failed")
                    .build();
        }
    }

    private Response<String> success(String data) {
        return Response.<String>builder()
                .code(Constants.ResponseCode.SUCCESS.getCode())
                .info(Constants.ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private Response<String> illegal(String message) {
        return Response.<String>builder()
                .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }
}
