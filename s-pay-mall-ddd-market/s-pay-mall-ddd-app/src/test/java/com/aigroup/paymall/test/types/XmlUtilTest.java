package com.aigroup.paymall.test.types;

import com.aigroup.paymall.types.sdk.weixin.MessageTextEntity;
import com.aigroup.paymall.types.sdk.weixin.XmlUtil;
import org.junit.Assert;
import org.junit.Test;

public class XmlUtilTest {

    @Test
    public void shouldRoundTripSpecialCharactersAndCdataBoundary() {
        MessageTextEntity source = new MessageTextEntity();
        source.setToUserName("to<&>");
        source.setFromUserName("from");
        source.setContent("a<>&]]>b");

        String xml = XmlUtil.beanToXml(source);
        MessageTextEntity restored = XmlUtil.xmlToBean(xml, MessageTextEntity.class);

        Assert.assertEquals(source.getToUserName(), restored.getToUserName());
        Assert.assertEquals(source.getContent(), restored.getContent());
    }

    @Test
    public void shouldRejectExternalEntities() {
        String xml = "<!DOCTYPE xml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<xml><Content>&xxe;</Content></xml>";

        Assert.assertThrows(IllegalStateException.class,
                () -> XmlUtil.xmlToBean(xml, MessageTextEntity.class));
    }
}
