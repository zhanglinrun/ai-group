package com.aigroup.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable integration message written in the same transaction as a user. */
@Data
@TableName("auth_outbox_event")
public class AuthOutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String eventType;
    /** Kafka topic. Column name `routing_key` is historical. */
    @TableField("routing_key")
    private String topic;
    private String aggregateId;
    private String traceId;
    private String payload;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime occurredAt;
    private LocalDateTime sentAt;
    private String lastError;
}
