package com.aigroup.member.job;

import com.aigroup.member.service.MemberService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpiredFreezeReleaseJobTest {

    @Test
    void managedFreezesAreOnlyReportedWhileLegacyFreezesAreReleased() {
        MemberService memberService = mock(MemberService.class);
        when(memberService.listExpiredManagedPendingFreezeIds(30, 200))
                .thenReturn(List.of("managed-1", "managed-2"));
        when(memberService.listExpiredPendingFreezeIds(30, 200))
                .thenReturn(List.of("legacy-1"));

        new ExpiredFreezeReleaseJob(memberService, 30, 200).releaseExpiredFreezes();

        verify(memberService).release("legacy-1");
        verify(memberService, never()).release("managed-1");
        verify(memberService, never()).release("managed-2");
    }
}
