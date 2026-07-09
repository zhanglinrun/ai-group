package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.auth.adapter.port.ILoginPort;
import com.aigroup.paymall.infrastructure.gateway.IWeixinApiService;
import com.aigroup.paymall.infrastructure.gateway.dto.WeixinQrCodeRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.WeixinQrCodeResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.WeixinTemplateMessageDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.WeixinTokenResponseDTO;
import com.google.common.cache.Cache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import retrofit2.Call;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import cn.hutool.core.util.IdUtil;

@Service
@ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
public class LoginPort implements ILoginPort {

    @Value("${weixin.config.app-id}")
    private String appid;
    @Value("${weixin.config.app-secret}")
    private String appSecret;
    @Value("${weixin.config.template_id}")
    private String template_id;

    @Resource
    private Cache<String, String> weixinAccessToken;

    @Resource
    private IWeixinApiService weixinApiService;

    /**
     * 鑾峰彇 ticket锛?
     * <a href="https://developers.weixin.qq.com/doc/offiaccount/Account_Management/Generating_a_Parametric_QR_Code.html">鑾峰彇 ticket API</a>
     */
    @Override
    public String createQrCodeTicket() throws IOException {
        String sceneStr = IdUtil.getSnowflake().nextIdStr();
        return createQrCodeTicket(sceneStr);
    }

    @Override
    public String createQrCodeTicket(String sceneStr) throws IOException {
        // 1. 鑾峰彇 accessToken
        String accessToken = weixinAccessToken.getIfPresent(appid);
        if (null == accessToken) {
            Call<WeixinTokenResponseDTO> call = weixinApiService.getToken("client_credential", appid, appSecret);
            WeixinTokenResponseDTO weixinTokenRes = call.execute().body();
            assert weixinTokenRes != null;
            accessToken = weixinTokenRes.getAccess_token();
            weixinAccessToken.put(appid, accessToken);
        }

        // 2. 鐢熸垚 ticket
        WeixinQrCodeRequestDTO weixinQrCodeReq = WeixinQrCodeRequestDTO.builder()
                .expire_seconds(2592000)
                .action_name(WeixinQrCodeRequestDTO.ActionNameTypeVO.QR_STR_SCENE.getCode())
                .action_info(WeixinQrCodeRequestDTO.ActionInfo.builder()
                        .scene(WeixinQrCodeRequestDTO.ActionInfo.Scene.builder()
                                .scene_str(sceneStr)
                                .build())
                        .build())
                .build();

        Call<WeixinQrCodeResponseDTO> call = weixinApiService.createQrCode(accessToken, weixinQrCodeReq);
        WeixinQrCodeResponseDTO weixinQrCodeRes = call.execute().body();
        assert null != weixinQrCodeRes;
        return weixinQrCodeRes.getTicket();
    }

    @Override
    public void sendLoginTemplate(String openid) throws IOException {
        // 1. 鑾峰彇 accessToken 銆愬疄闄呬笟鍔″満鏅紝鎸夐渶澶勭悊涓嬪紓甯搞??
        String accessToken = weixinAccessToken.getIfPresent(appid);
        if (null == accessToken){
            Call<WeixinTokenResponseDTO> call = weixinApiService.getToken("client_credential", appid, appSecret);
            WeixinTokenResponseDTO weixinTokenRes = call.execute().body();
            assert weixinTokenRes != null;
            accessToken = weixinTokenRes.getAccess_token();
            weixinAccessToken.put(appid, accessToken);
        }

        // 2. 鍙戦?佹ā鏉挎秷鎭?
        Map<String, Map<String, String>> data = new HashMap<>();
        WeixinTemplateMessageDTO.put(data, WeixinTemplateMessageDTO.TemplateKey.USER, openid);

        WeixinTemplateMessageDTO templateMessageDTO = new WeixinTemplateMessageDTO(openid, template_id);
        templateMessageDTO.setUrl("https://gaga.plus");
        templateMessageDTO.setData(data);

        Call<Void> call = weixinApiService.sendMessage(accessToken, templateMessageDTO);
        call.execute();
    }

}
