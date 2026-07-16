package com.aigroup.groupbuy.trigger.http;

import com.aigroup.groupbuy.api.dto.DemoFinalizeMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only team finalization used by the local interview demonstration payment flow.
 * The enclosing trade path is additionally protected by {@code InternalTokenAuthFilter}.
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(name = "ai-group.group.demo-finalize-enabled", havingValue = "true")
@RequestMapping("/api/v1/gbm/trade")
public class DemoMarketTradeController {

    private final ITradeSettlementOrderService settlementOrderService;

    public DemoMarketTradeController(ITradeSettlementOrderService settlementOrderService) {
        this.settlementOrderService = settlementOrderService;
    }

    @PostMapping("/demo_finalize_market_pay_order")
    public Response<String> finalizePaidOrder(@RequestBody DemoFinalizeMarketPayOrderRequestDTO request) {
        if (request == null || StringUtils.isBlank(request.getUserId())
                || StringUtils.isBlank(request.getOutTradeNo())) {
            return failure(ResponseCode.ILLEGAL_PARAMETER);
        }
        try {
            String teamId = settlementOrderService.finalizePaidTeamForDemo(
                    request.getUserId(), request.getOutTradeNo());
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(teamId)
                    .build();
        } catch (AppException e) {
            return Response.<String>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            return failure(ResponseCode.UN_ERROR);
        }
    }

    private Response<String> failure(ResponseCode code) {
        return Response.<String>builder().code(code.getCode()).info(code.getInfo()).build();
    }
}
