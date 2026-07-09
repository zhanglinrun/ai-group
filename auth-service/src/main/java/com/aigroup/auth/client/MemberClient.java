package com.aigroup.auth.client;

import com.aigroup.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

// url 为空时走 Nacos 服务发现（按 name=member 负载均衡）；local profile 设 ai-group.member.url 直连
@FeignClient(name = "member", url = "${ai-group.member.url:}")
public interface MemberClient {

    @PostMapping("/internal/members/init-free")
    Result<Void> initFree(@RequestBody Map<String, Long> body);
}
