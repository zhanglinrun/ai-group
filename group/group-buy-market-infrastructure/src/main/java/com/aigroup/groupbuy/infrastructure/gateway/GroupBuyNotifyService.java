package com.aigroup.groupbuy.infrastructure.gateway;

import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 拼团回调服务；成团回调向 pay 发送 HTTP 通知，并携带内部 token。
 */
@Slf4j
@Service
public class GroupBuyNotifyService {

    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Resource
    private OkHttpClient okHttpClient;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    public String groupBuyNotify(String apiUrl, String notifyRequestDTOJSON) throws Exception {
        try {
            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, notifyRequestDTOJSON);
            Request.Builder builder = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("content-type", "application/json");
            if (internalToken != null && !internalToken.isBlank()) {
                builder.addHeader(HEADER_INTERNAL_TOKEN, internalToken);
            }
            Request request = builder.build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                // 非 2xx 响应视为回调失败，返回 error 交由上层任务重试
                if (!response.isSuccessful()) {
                    log.error("拼团回调 HTTP 响应状态异常 {} httpCode:{}", apiUrl, response.code());
                    return NotifyTaskHTTPEnumVO.ERROR.getCode();
                }
                ResponseBody responseBody = response.body();
                return responseBody == null ? "" : responseBody.string();
            }
        } catch (Exception e) {
            log.error("拼团回调 HTTP 接口服务异常 {}", apiUrl, e);
            throw new AppException(ResponseCode.HTTP_EXCEPTION);
        }
    }
}
