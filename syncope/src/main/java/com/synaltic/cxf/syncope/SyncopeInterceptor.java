package com.synaltic.cxf.syncope;

import org.apache.cxf.common.security.SimpleGroup;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.security.DefaultSecurityContext;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.security.SecurityContext;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.http.Headers;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.felix.utils.json.JSONParser;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.wss4j.common.principal.WSUsernameTokenPrincipalImpl;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.message.token.UsernameToken;
import org.apache.wss4j.dom.validate.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.security.auth.Subject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.Principal;
import java.util.*;

/**
 * This interceptor just get a base authorization, and create a UsernameToken delegated to the Syncope interceptor
 */
public class SyncopeInterceptor extends AbstractPhaseInterceptor<Message> {

    private final Logger LOGGER = LoggerFactory.getLogger(SyncopeInterceptor.class);

    private Dictionary properties;
    private String busId;

    public SyncopeInterceptor() {
        this(Phase.READ);
    }

    public SyncopeInterceptor(String phase) {
        super(phase);
    }

    public void sendErrorResponse(Message message, int errorCode) {

        // no authentication provided, send error response
        Exchange exchange = message.getExchange();
        Message outMessage = exchange.getOutMessage();
        if (outMessage == null) {
            Endpoint endpoint = exchange.get(Endpoint.class);
            outMessage = new MessageImpl();
            outMessage.putAll(message);
            outMessage.remove(Message.PROTOCOL_HEADERS);
            outMessage.setExchange(exchange);
            outMessage = endpoint.getBinding().createMessage(outMessage);
            exchange.setOutMessage(outMessage);
        }
        outMessage.put(Message.RESPONSE_CODE, errorCode);
        Map<String, List<String>> responseHeaders = Headers.getSetProtocolHeaders(outMessage);
        responseHeaders.put("WWW-Authenticate", Arrays.asList(new String[] {"Basic realm=realm"}));
        message.getInterceptorChain().abort();

        try {
            EndpointReferenceType target = exchange.get(EndpointReferenceType.class);
            if (exchange.getDestination() == null) {
                LOGGER.debug("Exchange destination is null");
                return;
            }
            Conduit conduit = exchange.getDestination().getBackChannel(message);
            exchange.setConduit(conduit);
            conduit.prepare(outMessage);
            OutputStream os = outMessage.getContent(OutputStream.class);
            os.flush();
            os.close();
        } catch (Exception e) {
            LOGGER.error("Can't prepare response", e);
        }
    }

    public void handleMessage(Message message) throws Fault {
        AuthorizationPolicy policy = message.get(AuthorizationPolicy.class);

        if (policy == null || policy.getUserName() == null || policy.getPassword() == null) {
            // no authentication provided, send error response
            LOGGER.debug("Policy: {}", policy);
            LOGGER.debug("Username: {}", policy != null ? policy.getUserName() : null);
            LOGGER.debug("Password: {}", policy != null ? policy.getPassword() : null);
            sendErrorResponse(message, HttpURLConnection.HTTP_UNAUTHORIZED);
            return;
        }

        try {
            LOGGER.info("Get authorization policy, converting to username token");

            UsernameToken token = convertPolicyToToken(policy);
            Credential credential = new Credential();
            credential.setUsernametoken(token);

            RequestData data = new RequestData();
            data.setMsgContext(message);

            // Create a Principal/SecurityContext
            Principal p = null;
            if (credential != null && credential.getPrincipal() != null) {
                p = credential.getPrincipal();
            } else {
                p = new WSUsernameTokenPrincipalImpl(policy.getUserName(), false);
                ((WSUsernameTokenPrincipalImpl)p).setPassword(policy.getPassword());
            }

            // create the util and retrieve Syncope address
            InterceptorsUtil util = new InterceptorsUtil(properties);
            String address;
            try {
                address = util.getSyncopeAddress();
            } catch (Exception e) {
                LOGGER.error("Can't get Syncope address", e);
                throw new Fault(e);
            }

            String version;
            try {
                version = util.getSyncopeVersion();
            } catch (Exception e) {
                LOGGER.error("Can't get Syncope version", e);
                throw new Fault(e);
            }

            if (token.getName() == null || token.getName().isEmpty()) {
                throw new Fault(new SecurityException("Empty username is not allowed"));
            }
            if (token.getPassword() == null || token.getPassword().isEmpty()) {
                throw new Fault(new SecurityException("Empty password is not allowed"));
            }

            DefaultHttpClient client = new DefaultHttpClient();
            Credentials creds = new UsernamePasswordCredentials(token.getName(), token.getPassword());
            client.getCredentialsProvider().setCredentials(AuthScope.ANY, creds);
            HttpGet get = new HttpGet(address + "/users/self");
            if (version.equals("2.x") || version.equals("2")) {
                get.setHeader("Content-Type", "application/json");
            } else {
                get.setHeader("Content-Type", "application/xml");
            }

            List<String> roles;
            try {
                CloseableHttpResponse response = client.execute(get);
                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new Fault(new SecurityException("Can't authenticate user"));
                }
                String responseString = EntityUtils.toString(response.getEntity());
                if (version.equals("2.x") || version.equals("2")) {
                    roles = extractingRolesSyncope2(responseString);
                } else {
                    roles = extractingRolesSyncope1(responseString);
                }
            } catch (Exception e) {
                throw new Fault(e);
            }

            if (!util.authorize(busId, roles)) {
                throw new Fault(new SecurityException("Unauthorized"));
            }

            Subject subject = new Subject();
            subject.getPrincipals().add(p);
            for (String role : roles) {
                subject.getPrincipals().add(new SimpleGroup(role, token.getName()));
            }
            subject.setReadOnly();

            // put principal and subject (with the roles) in message DefaultSecurityContext
            message.put(DefaultSecurityContext.class, new DefaultSecurityContext(p, subject));

        } catch (Exception ex) {
            LOGGER.warn("Authentication failed", ex);
            sendErrorResponse(message, HttpURLConnection.HTTP_UNAUTHORIZED);
        }
    }

    protected UsernameToken convertPolicyToToken(AuthorizationPolicy policy)
            throws Exception {

        Document doc = DOMUtils.createDocument();
        UsernameToken token = new UsernameToken(false, doc, WSConstants.PASSWORD_TEXT);
        token.setName(policy.getUserName());
        token.setPassword(policy.getPassword());
        return token;
    }

    protected SecurityContext createSecurityContext(final Principal p) {
        return new SecurityContext() {

            public Principal getUserPrincipal() {
                return p;
            }

            public boolean isUserInRole(String arg0) {
                return false;
            }
        };
    }

    /**
     * Extract the user roles from the XML provided by Syncope 1.x.
     *
     * @param response the HTTP response from Syncope.
     * @return the list of user roles.
     * @throws Exception in case of extraction failure.
     */
    protected List<String> extractingRolesSyncope1(String response) throws Exception {
        List<String> roles = new ArrayList<String>();
        if (response != null && !response.isEmpty()) {
            // extract the <memberships> element if it exists
            int index = response.indexOf("<memberships>");
            if (index != -1) {
                response = response.substring(index + "<memberships>".length());
                index = response.indexOf("</memberships>");
                response = response.substring(0, index);

                // looking for the roleName elements
                index = response.indexOf("<roleName>");
                while (index != -1) {
                    response = response.substring(index + "<roleName>".length());
                    int end = response.indexOf("</roleName>");
                    if (end == -1) {
                        index = -1;
                    }
                    String role = response.substring(0, end);
                    roles.add(role);
                    response = response.substring(end + "</roleName>".length());
                    index = response.indexOf("<roleName>");
                }
            }

        }
        return roles;
    }

    /**
     * Extract the user roles from the JSON provided by Syncope 2.x.
     *
     * @param response the HTTP response from Syncope.
     * @return the list of user roles.
     * @throws Exception in case of extractiong failure.
     */
    @SuppressWarnings("unchecked")
    protected List<String> extractingRolesSyncope2(String response) throws Exception {
        List<String> roles = new ArrayList<String>();
        if (response != null && !response.isEmpty()) {
            JSONParser parser = new JSONParser(response);
            return (List<String>) parser.getParsed().get("roles");
        }
        return roles;
    }

    public void setBusId(String busId) {
        this.busId = busId;
    }

    public void setProperties(Dictionary properties) {
        this.properties = properties;
    }

}
