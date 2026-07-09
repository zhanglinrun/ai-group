package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.TeamRefundSuccessRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Group team_refund message listener: executes the alipay refund for orders
 * parked in WAIT_REFUND.
 *
 * @author xiaofuge bugstack.cn
 * 2025/8/1 09:52
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    // C5: queue declares dead-letter routing; combined with bounded listener retry
    // (default-requeue-rejected=false) poison messages land in the DLQ instead of
    // being redelivered forever. If an old queue WITHOUT these arguments already
    // exists locally, delete it once (management UI or rabbitmqctl delete_queue)
    // so it can be re-declared - RabbitMQ raises PRECONDITION_FAILED otherwise.
    // See RabbitMQDlqConfig.
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(
                            value = "${spring.rabbitmq.config.consumer.topic_team_refund.queue}",
                            arguments = {
                                    @Argument(name = "x-dead-letter-exchange", value = "${spring.rabbitmq.config.consumer.dlx_exchange}"),
                                    @Argument(name = "x-dead-letter-routing-key", value = "${spring.rabbitmq.config.consumer.topic_team_refund.dlq_routing_key}")
                            }
                    ),
                    exchange = @Exchange(value = "${spring.rabbitmq.config.consumer.topic_team_refund.exchange}", type = ExchangeTypes.TOPIC),
                    key = "${spring.rabbitmq.config.consumer.topic_team_refund.routing_key}"
            )
    )
    public void listener(String message) {
        try {
            log.info("team refund callback, start refund {}", message);
            TeamRefundSuccessRequestDTO requestDTO = JSON.parseObject(message, TeamRefundSuccessRequestDTO.class);
            String type = requestDTO.getType();
            if ("paid_unformed".equals(type) || "paid_formed".equals(type)) {
                boolean success = orderService.refundPayOrder(requestDTO.getUserId(), requestDTO.getOutTradeNo());
                // C3: a business failure (alipay refund rejected) must not be acked
                // silently or the refund is lost forever - throw to trigger the bounded
                // MQ retry, then the DLQ (C5) for manual replay
                if (!success) {
                    throw new AppException(ResponseCode.UN_ERROR.getCode(),
                            "refund pay order failed userId:" + requestDTO.getUserId() + " outTradeNo:" + requestDTO.getOutTradeNo());
                }
            }
        } catch (AlipayApiException ex) {
            throw new RuntimeException(ex);
        } catch (Exception e) {
            log.error("team refund callback, refund failed {}", message, e);
            throw e;
        }
    }

}
