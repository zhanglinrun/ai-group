package com.linrun.agent.domain.agent.reactor.util;


import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.reactor.model.req.GptQueryReq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ChateiUtils {
    private static final int MAX_REQUEST_ID_LENGTH = 64;

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
}
