package com.aigroup.paymall.domain.auth.adapter.port;

import java.io.IOException;

public interface ILoginPort {

    String createQrCodeTicket() throws IOException;

    String createQrCodeTicket(String sceneStr) throws IOException;

    void sendLoginTemplate(String openid) throws IOException;

}
