package com.aigroup.auth.client;

import com.aigroup.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "member", url = "${ai-group.member.url:http://127.0.0.1:8082}")
public interface MemberClient {

    @PostMapping("/internal/members/init-free")
    Result<Void> initFree(@RequestBody Map<String, Long> body);
}
