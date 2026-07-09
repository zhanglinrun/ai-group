package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.trigger.http.support.InternalCallbackAuthSupport;
import com.aigroup.paymall.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/internal/benefit-events")
public class BenefitEventInternalController {

    @Resource
    private IBenefitEventService benefitEventService;

    @Resource
    private InternalCallbackAuthSupport internalCallbackAuthSupport;

    @GetMapping("/pending-grants")
    public Response<List<BenefitEventEntity>> pendingGrants(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date since,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletRequest servletRequest) {
        // C6: internal ops endpoint, same X-Internal-Token check as active_pay_notify
        if (!internalCallbackAuthSupport.isAuthorized(servletRequest)) {
            log.warn("pending grants query rejected: missing or invalid internal token");
            return Response.<List<BenefitEventEntity>>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
        try {
            List<BenefitEventEntity> events = benefitEventService.queryPendingGrants(since, lastId, pageSize);
            return Response.<List<BenefitEventEntity>>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(events)
                    .build();
        } catch (Exception e) {
            log.error("query pending benefit grants failed", e);
            return Response.<List<BenefitEventEntity>>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
