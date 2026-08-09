package com.aigroup.member.dto;

import lombok.Data;

/** Identity event delivered by Auth; account creation is idempotent in Member. */
@Data
public class UserRegisteredEvent {
    private String eventId;
    private String eventType;
    private Long userId;
    private String username;
    private String role;
}
