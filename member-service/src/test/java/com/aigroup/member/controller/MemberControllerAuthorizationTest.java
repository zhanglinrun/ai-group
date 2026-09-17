package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemberControllerAuthorizationTest {

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void userDataEndpointsRejectDirectCallsWithoutVerifiedIdentity() {
        MemberController controller = new MemberController(null);
        assertThrows(BusinessException.class, controller::summary);
        assertThrows(BusinessException.class, controller::quotaLedger);
    }

    @Test
    void createReservationRejectsUserIdMismatchWhenJwtIsBound() {
        MemberService memberService = mock(MemberService.class);
        MemberController controller = new MemberController(memberService);
        RequestUserContext.bind(1001L, "alice", "USER");

        assertThrows(BusinessException.class, () -> controller.createReservation(Map.of(
                "userId", 2002L,
                "amount", 100L
        )));
        verifyNoInteractions(memberService);
    }

    @Test
    void createReservationAllowsMatchingJwtSubject() {
        MemberService memberService = mock(MemberService.class);
        when(memberService.freeze(eq(1001L), eq(100L), eq(100L), eq("llm"), isNull(), eq("legacy"), isNull()))
                .thenReturn(Map.of("freezeId", "f1"));
        MemberController controller = new MemberController(memberService);
        RequestUserContext.bind(1001L, "alice", "USER");

        controller.createReservation(Map.of("userId", 1001L, "amount", 100L));

        verify(memberService).freeze(eq(1001L), eq(100L), eq(100L), eq("llm"), isNull(), eq("legacy"), isNull());
    }

    @Test
    void createReservationAllowsTokenOnlyWhenNoJwtIsBound() {
        MemberService memberService = mock(MemberService.class);
        when(memberService.freeze(anyLong(), anyLong(), anyLong(), anyString(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("freezeId", "f1"));
        MemberController controller = new MemberController(memberService);

        controller.createReservation(Map.of("userId", 3003L, "amount", 50L));

        verify(memberService).freeze(eq(3003L), eq(50L), eq(50L), eq("llm"), isNull(), eq("legacy"), isNull());
    }

    @Test
    void createDebitRejectsUserIdMismatchWhenJwtIsBound() {
        MemberService memberService = mock(MemberService.class);
        MemberController controller = new MemberController(memberService);
        RequestUserContext.bind(1001L, "alice", "USER");

        assertThrows(BusinessException.class, () -> controller.createDebit(Map.of(
                "userId", 2002L,
                "amount", 100L,
                "requestId", "agent:run:call:1"
        )));
        verifyNoInteractions(memberService);
    }

    @Test
    void createDebitAllowsMatchingJwtSubject() {
        MemberService memberService = mock(MemberService.class);
        when(memberService.debit(eq(1001L), eq(100L), eq("llm"), eq("agent:run:call:1"),
                eq("agent-service"), eq("run_1"))).thenReturn(Map.of("debitId", "d1"));
        MemberController controller = new MemberController(memberService);
        RequestUserContext.bind(1001L, "alice", "USER");

        controller.createDebit(Map.of(
                "userId", 1001L,
                "amount", 100L,
                "requestId", "agent:run:call:1",
                "ownerService", "agent-service",
                "traceId", "run_1"
        ));

        verify(memberService).debit(eq(1001L), eq(100L), eq("llm"), eq("agent:run:call:1"),
                eq("agent-service"), eq("run_1"));
    }
}
