package com.synaltic.cxf.logging;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.LoggingMessage;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.io.CachedWriter;
import org.apache.cxf.io.DelegatingInputStream;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.staxutils.PrettyPrintXMLStreamWriter;
import org.apache.cxf.staxutils.StaxUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.io.StringWriter;

public class SynalticLoggingInterceptor extends AbstractPhaseInterceptor<Message> {

    private Logger logger;

    public SynalticLoggingInterceptor(String loggerName, String phase) {
        // in logging should use receive
        // out logging should use pre-stream
        super(phase);
        logger = LoggerFactory.getLogger(loggerName);
    }

    public void handleMessage(Message message) {
        if (!message.containsKey(LoggingMessage.ID_KEY)) {
            String id = (String) message.getExchange().get(LoggingMessage.ID_KEY);
            if (id == null) {
                id = LoggingMessage.nextId();
                message.getExchange().put(LoggingMessage.ID_KEY, id);
            }

            message.put(LoggingMessage.ID_KEY, id);
            StringBuilder buffer = new StringBuilder();

            buffer.append("------------------------------------------\n");

            if (!Boolean.TRUE.equals(message.get("decoupled.channel.message"))) {
                Integer encoding = (Integer) message.get(Message.RESPONSE_CODE);
                if (encoding != null) {
                    buffer.append("ResponseCode: ").append(encoding).append("\n");
                }
            }

            String encoding = (String) message.get(Message.ENCODING);
            if (encoding != null) {
                buffer.append("Encoding: ").append(encoding).append("\n");
            }

            String httpMethod = (String) message.get("org.apache.cxf.request.method");
            if (httpMethod != null) {
                buffer.append("HTTP Method: ").append(httpMethod).append("\n");
            }

            String ct = (String) message.get("Content-Type");
            if (ct != null) {
                buffer.append("Content Type: ").append(ct).append("\n");
            }

            Object headers = message.get(Message.PROTOCOL_HEADERS);
            if (headers != null) {
                buffer.append("Headers: ").append(headers).append("\n");
            }

            String uri = (String) message.get("org.apache.cxf.request.url");
            String is;
            if (uri == null) {
                is = (String) message.get(Message.ENDPOINT_ADDRESS);
                uri = (String) message.get("org.apache.cxf.request.uri");
                if (uri != null && uri.startsWith("/")) {
                    if (is != null && !is.startsWith(uri)) {
                        if (is.endsWith("/") && is.length() > 1) {
                            is = is.substring(0, is.length());
                        }

                        uri = is + uri;
                    }
                } else {
                    uri = is;
                }
            }

            if (uri != null) {
                buffer.append("Address: ").append(uri);
                is = (String) message.get(Message.QUERY_STRING);
                if (is != null) {
                    buffer.append("?").append(is);
                }
                buffer.append("\n");
            }

            InputStream is1 = (InputStream) message.getContent(InputStream.class);
            if (is1 != null) {
                this.logInputStream(message, is1, buffer, encoding, ct);
            } else {
                Reader reader = (Reader) message.getContent(Reader.class);
                if (reader != null) {
                    this.logReader(message, reader, buffer);
                }
            }

            MDC.put("cxf.logging.key", id);
            logger.info(buffer.toString());
        }
    }

    protected void logReader(Message message, Reader reader, StringBuilder buffer) {
        try {
            CachedWriter e = new CachedWriter();
            IOUtils.copyAndCloseInput(reader, e);
            message.setContent(Reader.class, e.getReader());
            if(e.getTempFile() != null) {
                buffer.append("\nMessage (saved to tmp file):\n");
                buffer.append("Filename: " + e.getTempFile().getAbsolutePath() + "\n");
            }

            e.writeCacheTo(buffer);
        } catch (Exception var5) {
            throw new Fault(var5);
        }
    }

    protected void logInputStream(Message message, InputStream is, StringBuilder buffer, String encoding, String ct) {
        CachedOutputStream bos = new CachedOutputStream();

        try {
            InputStream e = is instanceof DelegatingInputStream ?((DelegatingInputStream)is).getInputStream():is;
            IOUtils.copyAtLeast(e, bos, 2147483647);
            bos.flush();
            SequenceInputStream e1 = new SequenceInputStream(bos.getInputStream(), e);
            if(is instanceof DelegatingInputStream) {
                ((DelegatingInputStream)is).setInputStream(e1);
            } else {
                message.setContent(InputStream.class, e1);
            }

            if(bos.getTempFile() != null) {
                buffer.append("\nMessage (saved to tmp file):\n");
                buffer.append("Filename: " + bos.getTempFile().getAbsolutePath() + "\n");
            }

            this.writePayload(buffer, bos, encoding, ct);
            bos.close();
        } catch (Exception var8) {
            throw new Fault(var8);
        }
    }

    protected void writePayload(StringBuilder builder, CachedOutputStream cos, String encoding, String contentType) throws Exception {
        if(contentType != null && contentType.indexOf("xml") >= 0 && contentType.toLowerCase().indexOf("multipart/related") < 0 && cos.size() > 0L) {
            StringWriter swriter = new StringWriter();
            XMLStreamWriter xwriter = StaxUtils.createXMLStreamWriter(swriter);
            PrettyPrintXMLStreamWriter xwriter1 = new PrettyPrintXMLStreamWriter(xwriter, 2);
            InputStream in = cos.getInputStream();

            try {
                StaxUtils.copy(new StreamSource(in), xwriter1);
            } catch (XMLStreamException var17) {
                ;
            } finally {
                try {
                    xwriter1.flush();
                    xwriter1.close();
                } catch (XMLStreamException var16) {
                    ;
                }

                in.close();
            }

            String result = swriter.toString();
            builder.append(swriter.toString());
        } else if(StringUtils.isEmpty(encoding)) {
            cos.writeCacheTo(builder);
        } else {
            cos.writeCacheTo(builder, encoding);
        }

    }

}
