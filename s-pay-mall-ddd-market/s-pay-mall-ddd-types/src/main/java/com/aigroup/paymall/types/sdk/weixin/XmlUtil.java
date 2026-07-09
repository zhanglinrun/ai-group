package com.aigroup.paymall.types.sdk.weixin;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.core.util.QuickWriter;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.io.xml.PrettyPrintWriter;
import com.thoughtworks.xstream.io.xml.XppDriver;
import com.thoughtworks.xstream.security.AnyTypePermission;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;

public class XmlUtil {

    /**
     * 瑙ｆ瀽寰俊鍙戞潵鐨勮姹?xml)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> xmlToMap(HttpServletRequest request) throws Exception {
        // 浠巖equest涓彇寰楄緭鍏ユ祦
        try (InputStream inputStream = request.getInputStream()) {
            // 灏嗚В鏋愮粨鏋滃瓨鍌ㄥ湪HashMap涓?
            Map<String, String> map = new HashMap<>();
            // 璇诲彇杈撳叆娴?
            SAXReader reader = new SAXReader();
            // 寰楀埌xml鏂囨。
            Document document = reader.read(inputStream);
            // 寰楀埌xml鏍瑰厓绱?
            Element root = document.getRootElement();
            // 寰楀埌鏍瑰厓绱犵殑鎵?鏈夊瓙鑺傜偣
            List<Element> elementList = root.elements();
            // 閬嶅巻鎵?鏈夊瓙鑺傜偣
            for (Element e : elementList)
                map.put(e.getName(), e.getText());
            // 閲婃斁璧勬簮
            inputStream.close();
            return map;
        }
    }

    /**
     * 灏唌ap杞寲鎴恱ml鍝嶅簲缁欏井淇℃湇鍔″櫒
     */
    static String mapToXML(Map map) {
        StringBuffer sb = new StringBuffer();
        sb.append("<xml>");
        mapToXML2(map, sb);
        sb.append("</xml>");
        try {
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void mapToXML2(Map map, StringBuffer sb) {
        Set set = map.keySet();
        for (Object o : set) {
            String key = (String) o;
            Object value = map.get(key);
            if (null == value)
                value = "";
            if (value.getClass().getName().equals("java.util.ArrayList")) {
                ArrayList list = (ArrayList) map.get(key);
                sb.append("<").append(key).append(">");
                for (Object o1 : list) {
                    HashMap hm = (HashMap) o1;
                    mapToXML2(hm, sb);
                }
                sb.append("</").append(key).append(">");

            } else {
                if (value instanceof HashMap) {
                    sb.append("<").append(key).append(">");
                    mapToXML2((HashMap) value, sb);
                    sb.append("</").append(key).append(">");
                } else {
                    sb.append("<").append(key).append("><![CDATA[").append(value).append("]]></").append(key).append(">");
                }

            }

        }
    }

    public static XStream getMyXStream() {
        return new XStream(new XppDriver() {
            @Override
            public HierarchicalStreamWriter createWriter(Writer out) {
                return new PrettyPrintWriter(out) {
                    // 瀵规墍鏈墄ml鑺傜偣閮藉鍔燙DATA鏍囪
                    boolean cdata = true;

                    @Override
                    public void startNode(String name, Class clazz) {
                        super.startNode(name, clazz);
                    }

                    @Override
                    protected void writeText(QuickWriter writer, String text) {
                        if (cdata && !StringUtils.isNumeric(text)) {
                            writer.write("<![CDATA[");
                            writer.write(text);
                            writer.write("]]>");
                        } else {
                            writer.write(text);
                        }
                    }
                };
            }
        });
    }

    /**
     * bean杞垚寰俊鐨剎ml娑堟伅鏍煎紡
     */
    public static String beanToXml(Object object) {
        XStream xStream = getMyXStream();
        xStream.alias("xml", object.getClass());
        xStream.processAnnotations(object.getClass());
        String xml = xStream.toXML(object);
        if (!StringUtils.isEmpty(xml)) {
            return xml;
        } else {
            return null;
        }
    }

    /**
     * xml杞垚bean娉涘瀷鏂规硶
     */
    public static <T> T xmlToBean(String resultXml, Class clazz) {
        // XStream瀵硅薄璁剧疆榛樿瀹夊叏闃叉姢锛屽悓鏃惰缃厑璁哥殑绫?
        XStream stream = new XStream(new DomDriver());
        stream.addPermission(AnyTypePermission.ANY);
        XStream.setupDefaultSecurity(stream);
        stream.allowTypes(new Class[]{clazz});
        stream.processAnnotations(new Class[]{clazz});
        stream.setMode(XStream.NO_REFERENCES);
        stream.alias("xml", clazz);
        return (T) stream.fromXML(resultXml);
    }

}