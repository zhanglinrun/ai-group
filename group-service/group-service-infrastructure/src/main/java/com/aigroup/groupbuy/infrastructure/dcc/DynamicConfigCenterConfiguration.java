package com.aigroup.groupbuy.infrastructure.dcc;

import com.aigroup.groupbuy.types.dcc.DccAttributeVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DynamicConfigProperties.class)
@RequiredArgsConstructor
public class DynamicConfigCenterConfiguration {

    public static final String DCC_TOPIC_BEAN = "dynamicConfigCenterRedisTopic";

    private final DynamicConfigProperties properties;
    private final DynamicConfigHolder holder;
    private final RedissonClient redissonClient;

    @PostConstruct
    void seedDefaults() {
        holder.putAll(properties.getDefaults());
    }

    @Bean(name = DCC_TOPIC_BEAN)
    public RTopic dynamicConfigCenterRedisTopic() {
        RTopic topic = redissonClient.getTopic("group-service-dcc");
        topic.addListener(DccAttributeVO.class, (channel, msg) -> {
            if (msg != null && msg.getKey() != null) {
                holder.put(msg.getKey(), msg.getValue());
            }
        });
        return topic;
    }
}
