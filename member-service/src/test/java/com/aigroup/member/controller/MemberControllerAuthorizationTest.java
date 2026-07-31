package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberControllerAuthorizationTest {

    private final MemberController controller = new MemberController(null);

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void userDataEndpointsRejectDirectCallsWithoutVerifiedIdentity() {
        assertThrows(BusinessException.class, controller::summary);
        assertThrows(BusinessException.class, controller::quotaLedger);
    }
}
