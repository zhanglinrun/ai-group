package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.domain.auth.service.ILoginService;
import com.aigroup.paymall.types.sdk.weixin.MessageTextEntity;
import com.aigroup.paymall.types.sdk.weixin.SignatureUtil;
import com.aigroup.paymall.types.sdk.weixin.XmlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 微信服务对接，对接地址：/api/v1/weixin/portal/receive
 * <p>
 * 微信公众号测试平台：https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo?action=showinfo&t=sandbox/index
 * api指南：https://developers.weixin.qq.com/doc/service/guide/product/message/Receiving_standard_messages.html
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
@RequestMapping("/api/v1/weixin/portal/")
public class WeixinPortalController {

    @Value("${weixin.config.originalid}")
    private String originalid;
    @Value("${weixin.config.token}")
    private String token;

    @Resource
    private ILoginService loginService;

    /**
     * 验签
     */
    @GetMapping(value = "receive", produces = "text/plain;charset=utf-8")
    public String validate(@RequestParam(value = "signature", required = false) String signature,
                           @RequestParam(value = "timestamp", required = false) String timestamp,
                           @RequestParam(value = "nonce", required = false) String nonce,
                           @RequestParam(value = "echostr", required = false) String echostr) {
        try {
            log.info("微信公众号验签信息开始 [{}, {}, {}, {}]", signature, timestamp, nonce, echostr);
            if (StringUtils.isAnyBlank(signature, timestamp, nonce, echostr)) {
                throw new IllegalArgumentException("请求参数非法，请核实!");
            }
            boolean check = SignatureUtil.check(token, signature, timestamp, nonce);
            log.info("微信公众号验签信息完成 check：{}", check);
            if (!check) {
                return null;
            }
            return echostr;
        } catch (Exception e) {
            log.error("微信公众号验签信息失败 [{}, {}, {}, {}]", signature, timestamp, nonce, echostr, e);
            return null;
        }
    }

    /**
     * 回调，接收公众号消息【扫描登录，会接收到消息】
     */
    @PostMapping(value = "receive", produces = "application/xml; charset=UTF-8")
    public String post(@RequestBody String requestBody,
                       @RequestParam("signature") String signature,
                       @RequestParam("timestamp") String timestamp,
                       @RequestParam("nonce") String nonce,
                       @RequestParam("openid") String openid,
                       @RequestParam(name = "encrypt_type", required = false) String encType,
                       @RequestParam(name = "msg_signature", required = false) String msgSignature) {
        try {
            log.info("接收微信公众号信息请求 {}开始 {}", openid, requestBody);
            // 消息转换
            MessageTextEntity message = XmlUtil.xmlToBean(requestBody, MessageTextEntity.class);

            if ("event".equals(message.getMsgType()) && "SCAN".equals(message.getEvent())) {
                // 扫码登录【消息类型和事件】
                loginService.saveLoginState(message.getTicket(), openid);
                return buildMessageTextEntity(openid, "扫码登录成功");
            } else if ("event".equals(message.getMsgType()) && "subscribe".equals(message.getEvent())) {
                // 首次关注公众号也视为扫码登录（未关注用户扫码先触发 subscribe 再触发 SCAN）
                loginService.saveLoginState(message.getTicket(), openid);
                return buildMessageTextEntity(openid, "关注公众号成功");
            }

            return buildMessageTextEntity(openid, "你好：" + message.getContent());
        } catch (Exception e) {
            log.error("接收微信公众号信息请求 {}失败 {}", openid, requestBody, e);
            return "";
        }
    }

    private String buildMessageTextEntity(String openid, String content) {
        MessageTextEntity res = new MessageTextEntity();
        // 公众号分配的ID
        res.setFromUserName(originalid);
        res.setToUserName(openid);
        res.setCreateTime(String.valueOf(System.currentTimeMillis() / 1000L));
        res.setMsgType("text");
        res.setContent(content);
        return XmlUtil.beanToXml(res);
    }

}
