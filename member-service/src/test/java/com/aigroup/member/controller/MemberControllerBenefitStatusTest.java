package com.aigroup.member.controller;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.filter.InternalApiAuthFilter;
import com.aigroup.member.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberControllerBenefitStatusTest {

    private MemberService memberService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        memberService = mock(MemberService.class);
        InternalTokenProperties tokenProperties = new InternalTokenProperties();
        tokenProperties.setToken("internal-test-token");
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(memberService))
                .addFilters(new InternalApiAuthFilter(tokenProperties)).build();
    }

    @Test
    void grantDetailsRequireInternalTokenAndDoNotLeakExtraEventFields() throws Exception {
        mvc.perform(get("/internal/benefits/orders/order-1/status"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/benefits/orders/order-1/status")
                        .header(CommonConstant.HEADER_INTERNAL_TOKEN, "wrong-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(memberService);

        when(memberService.benefitGrantDetailsForOrder("order-1")).thenReturn(Map.of(
                "status", "GRANTED", "userId", "1001", "productCode", "QUOTA_500",
                "grantedQuotaMicro", "500000000"));
        mvc.perform(get("/internal/benefits/orders/order-1/status")
                        .header(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.userId").value("1001"))
                .andExpect(jsonPath("$.data.productCode").value("QUOTA_500"))
                .andExpect(jsonPath("$.data.grantedQuotaMicro").value("500000000"))
                .andExpect(jsonPath("$.data", aMapWithSize(4)));
        verify(memberService).benefitGrantDetailsForOrder("order-1");
        verifyNoMoreInteractions(memberService);
    }

    @Test
    void pendingAndRevokedReturnStatusOnly() throws Exception {
        when(memberService.benefitGrantDetailsForOrder("pending"))
                .thenReturn(Map.of("status", "PENDING"));
        when(memberService.benefitGrantDetailsForOrder("revoked"))
                .thenReturn(Map.of("status", "REVOKED"));

        mvc.perform(get("/internal/benefits/orders/pending/status")
                        .header(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data", aMapWithSize(1)));
        mvc.perform(get("/internal/benefits/orders/revoked/status")
                        .header(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data", aMapWithSize(1)));
        verify(memberService).benefitGrantDetailsForOrder("pending");
        verify(memberService).benefitGrantDetailsForOrder("revoked");
        verifyNoMoreInteractions(memberService);
    }

    @Test
    void rejectedRevocationKeepsGrantedAndSignalsManualReview() throws Exception {
        when(memberService.benefitGrantDetailsForOrder("conflict"))
                .thenReturn(Map.of("status", "GRANTED", "manualReview", true,
                        "userId", "1001", "productCode", "QUOTA_500",
                        "grantedQuotaMicro", "500000000"));

        mvc.perform(get("/internal/benefits/orders/conflict/status")
                        .header(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.manualReview").value(true))
                .andExpect(jsonPath("$.data", aMapWithSize(5)));
        verify(memberService).benefitGrantDetailsForOrder("conflict");
        verifyNoMoreInteractions(memberService);
    }
}
