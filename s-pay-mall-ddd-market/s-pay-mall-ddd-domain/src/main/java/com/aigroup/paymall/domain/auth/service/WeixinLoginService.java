package com.aigroup.paymall.domain.auth.service;

import com.aigroup.paymall.domain.auth.adapter.port.ILoginPort;
import com.google.common.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;

@Slf4j
@Service
@ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
public class WeixinLoginService implements ILoginService {

    @Resource
    private ILoginPort loginPort;
    @Resource
    private Cache<String, String> openidToken;

    @Override
    public String createQrCodeTicket() throws Exception {
        return loginPort.createQrCodeTicket();
    }

    @Override
    public String createQrCodeTicket(String sceneStr) throws Exception {
        String ticket = loginPort.createQrCodeTicket(sceneStr);
        // 淇濆瓨娴忚鍣ㄦ寚绾逛俊鎭拰ticket鏄犲皠鍏崇郴
        openidToken.put(sceneStr, ticket);
        return ticket;
    }

    @Override
    public String checkLogin(String ticket) {
        return openidToken.getIfPresent(ticket);
    }

    @Override
    public String checkLogin(String ticket, String sceneStr) {
        String cacheTicket = openidToken.getIfPresent(sceneStr);
        if (StringUtils.isBlank(cacheTicket) || !cacheTicket.equals(ticket)) return null;
        return checkLogin(ticket);
    }

    @Override
    public void saveLoginState(String ticket, String openid) throws IOException {
        // 淇濆瓨鐧诲綍淇℃伅
        openidToken.put(ticket, openid);
        // 鍙戦?佹ā鏉挎秷鎭?
        loginPort.sendLoginTemplate(openid);
    }

}
