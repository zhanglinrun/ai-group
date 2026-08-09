package com.aigroup.member.mq;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BenefitEventConsumer {

    private final MemberService memberService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "member-service.benefit")
    public void onTradeCompleted(String payload) {
        try {
            TradeCompletedEvent event = objectMapper.readValue(payload, TradeCompletedEvent.class);
            log.info("Received trade completed event");
            memberService.handleBenefitEvent(event);
        } catch (Exception ex) {
            log.error("Failed to consume TradeCompletedEvent, errorType={}", ex.getClass().getSimpleName());
            throw new IllegalStateException("TradeCompletedEvent consume failed", ex);
        }
    }
}
