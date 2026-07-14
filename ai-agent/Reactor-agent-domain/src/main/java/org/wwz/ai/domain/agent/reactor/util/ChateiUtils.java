package org.wwz.ai.domain.agent.reactor.util;


import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.enums.AutoBotsResultStatus;
import org.wwz.ai.domain.agent.reactor.model.dto.AutoBotsResult;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ChateiUtils {
    public static final String SOURCE_MOBILE = "mobile";
    public static final String SOURCE_PC = "pc";
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final String NO_ANSWER = "哎呀，超出我的知识领域了，换个问题试试吧";

    public static String getRequestId(GptQueryReq request) {
        return getRequestId(request.getUser(), request.getSessionId(), request.getRequestId());
    }

    public static String getRequestId(String erp, String traceId, String reqId) {
        erp = StringUtils.defaultString(erp).toLowerCase();
        traceId = StringUtils.defaultString(traceId);
        reqId = StringUtils.defaultString(reqId);
        String requestId;
        if (ChineseCharacterCounter.hasChineseCharacters(erp)) {
            requestId = traceId + ":" + reqId;
        } else {
            requestId = erp + traceId + ":" + reqId;
        }
        return requestId.length() <= MAX_REQUEST_ID_LENGTH ? requestId : sha256(requestId);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
    public static AutoBotsResult toAutoBotsResult(AgentRequest request, String status) {
        AutoBotsResult result = new AutoBotsResult();
        result.setTraceId(request.getRequestId());
        result.setReqId(request.getRequestId());
        result.setStatus(status);
        if (AutoBotsResultStatus.no.name().equals(status)) {
            result.setFinished(true);
            result.setResponse(NO_ANSWER);
            result.setResponseAll(NO_ANSWER);
        }
        return result;
    }
}
