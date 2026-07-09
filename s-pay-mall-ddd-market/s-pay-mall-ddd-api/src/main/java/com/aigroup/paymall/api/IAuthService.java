package com.aigroup.paymall.api;

import com.aigroup.paymall.api.response.Response;

public interface IAuthService {

    Response<String> weixinQrCodeTicket();

    Response<String> weixinQrCodeTicket(String sceneStr);

    Response<String> checkLogin(String ticket);

    Response<String> checkLogin(String ticket, String sceneStr);

}
