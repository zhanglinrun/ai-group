package com.aigroup.paymall.infrastructure.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberBenefitStatusDTO {
    private String status;
    private String userId;
    private String productCode;
    private String grantedQuotaMicro;
    private Boolean manualReview;
}
