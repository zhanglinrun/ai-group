package com.aigroup.paymall.types.sdk.weixin;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XML utility backed by JAXB (replacing dom4j + xstream).
 * Provides bean&lt;-&gt;XML conversion for WeChat message entities, with CDATA-wrapped text nodes
 * matching the legacy xstream output format expected by the WeChat MP API.
 */
public class XmlUtil {

    private static final ConcurrentHashMap<Class<?>, JAXBContext> CONTEXT_CACHE = new ConcurrentHashMap<>();

    private XmlUtil() {
    }

    private static JAXBContext contextOf(Class<?> clazz) {
        return CONTEXT_CACHE.computeIfAbsent(clazz, c -> {
            try {
                return JAXBContext.newInstance(c);
            } catch (JAXBException e) {
                throw new IllegalStateException("init JAXBContext for " + c.getName() + " failed", e);
            }
        });
    }

    /**
     * bean 转成微信的 xml 消息格式（CDATA 包裹文本节点）。
     */
    public static String beanToXml(Object object) {
        if (object == null) {
            return null;
        }
        try {
            JAXBContext context = contextOf(object.getClass());
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            XMLStreamWriter streamWriter = new CdataXmlStreamWriter(
                    javax.xml.stream.XMLOutputFactory.newInstance().createXMLStreamWriter(writer));
            marshaller.marshal(object, streamWriter);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("bean to xml failed: " + object.getClass(), e);
        }
    }

    /**
     * xml 转成 bean 泛型方法。
     */
    @SuppressWarnings("unchecked")
    public static <T> T xmlToBean(String resultXml, Class<T> clazz) {
        if (resultXml == null || resultXml.isBlank()) {
            return null;
        }
        XMLStreamReader reader = null;
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            reader = factory.createXMLStreamReader(new StringReader(resultXml));
            JAXBContext context = contextOf(clazz);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            return (T) unmarshaller.unmarshal(reader);
        } catch (JAXBException | XMLStreamException | IllegalArgumentException e) {
            throw new IllegalStateException("xml to bean failed: " + clazz.getName(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Parsing has already completed or failed; there is no recoverable resource state here.
                }
            }
        }
    }

    /**
     * XMLStreamWriter wrapper that wraps every text node in CDATA sections,
     * matching the legacy xstream PrettyPrintWriter CDATA behaviour for WeChat XML.
     */
    private static final class CdataXmlStreamWriter implements XMLStreamWriter {

        private final XMLStreamWriter delegate;

        CdataXmlStreamWriter(XMLStreamWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void writeCharacters(String text) throws XMLStreamException {
            writeCDataSafely(text);
        }

        @Override
        public void writeCharacters(char[] text, int start, int len) throws XMLStreamException {
            writeCDataSafely(new String(text, start, len));
        }

        @Override
        public void writeStartElement(String localName) throws XMLStreamException {
            delegate.writeStartElement(localName);
        }

        @Override
        public void writeStartElement(String namespaceURI, String localName) throws XMLStreamException {
            delegate.writeStartElement(namespaceURI, localName);
        }

        @Override
        public void writeStartElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
            delegate.writeStartElement(prefix, localName, namespaceURI);
        }

        @Override
        public void writeEmptyElement(String namespaceURI, String localName) throws XMLStreamException {
            delegate.writeEmptyElement(namespaceURI, localName);
        }

        @Override
        public void writeEmptyElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
            delegate.writeEmptyElement(prefix, localName, namespaceURI);
        }

        @Override
        public void writeEmptyElement(String localName) throws XMLStreamException {
            delegate.writeEmptyElement(localName);
        }

        @Override
        public void writeEndElement() throws XMLStreamException {
            delegate.writeEndElement();
        }

        @Override
        public void writeEndDocument() throws XMLStreamException {
            delegate.writeEndDocument();
        }

        @Override
        public void close() throws XMLStreamException {
            delegate.close();
        }

        @Override
        public void flush() throws XMLStreamException {
            delegate.flush();
        }

        @Override
        public void writeAttribute(String localName, String value) throws XMLStreamException {
            delegate.writeAttribute(localName, value);
        }

        @Override
        public void writeAttribute(String prefix, String namespaceURI, String localName, String value)
                throws XMLStreamException {
            delegate.writeAttribute(prefix, namespaceURI, localName, value);
        }

        @Override
        public void writeAttribute(String namespaceURI, String localName, String value) throws XMLStreamException {
            delegate.writeAttribute(namespaceURI, localName, value);
        }

        @Override
        public void writeNamespace(String prefix, String namespaceURI) throws XMLStreamException {
            delegate.writeNamespace(prefix, namespaceURI);
        }

        @Override
        public void writeDefaultNamespace(String namespaceURI) throws XMLStreamException {
            delegate.writeDefaultNamespace(namespaceURI);
        }

        @Override
        public void writeComment(String data) throws XMLStreamException {
            delegate.writeComment(data);
        }

        @Override
        public void writeProcessingInstruction(String target) throws XMLStreamException {
            delegate.writeProcessingInstruction(target);
        }

        @Override
        public void writeProcessingInstruction(String target, String data) throws XMLStreamException {
            delegate.writeProcessingInstruction(target, data);
        }

        @Override
        public void writeCData(String data) throws XMLStreamException {
            writeCDataSafely(data);
        }

        private void writeCDataSafely(String text) throws XMLStreamException {
            int start = 0;
            int boundary;
            while ((boundary = text.indexOf("]]>", start)) >= 0) {
                delegate.writeCData(text.substring(start, boundary + 2));
                start = boundary + 2;
            }
            delegate.writeCData(text.substring(start));
        }

        @Override
        public void writeDTD(String dtd) throws XMLStreamException {
            delegate.writeDTD(dtd);
        }

        @Override
        public void writeEntityRef(String name) throws XMLStreamException {
            delegate.writeEntityRef(name);
        }

        @Override
        public void writeStartDocument() throws XMLStreamException {
            delegate.writeStartDocument();
        }

        @Override
        public void writeStartDocument(String version) throws XMLStreamException {
            delegate.writeStartDocument(version);
        }

        @Override
        public void writeStartDocument(String encoding, String version) throws XMLStreamException {
            delegate.writeStartDocument(encoding, version);
        }

        @Override
        public String getPrefix(String uri) throws XMLStreamException {
            return delegate.getPrefix(uri);
        }

        @Override
        public void setPrefix(String prefix, String uri) throws XMLStreamException {
            delegate.setPrefix(prefix, uri);
        }

        @Override
        public void setDefaultNamespace(String uri) throws XMLStreamException {
            delegate.setDefaultNamespace(uri);
        }

        @Override
        public void setNamespaceContext(javax.xml.namespace.NamespaceContext context) throws XMLStreamException {
            delegate.setNamespaceContext(context);
        }

        @Override
        public javax.xml.namespace.NamespaceContext getNamespaceContext() {
            return delegate.getNamespaceContext();
        }

        @Override
        public Object getProperty(String name) throws IllegalArgumentException {
            return delegate.getProperty(name);
        }
    }
}
