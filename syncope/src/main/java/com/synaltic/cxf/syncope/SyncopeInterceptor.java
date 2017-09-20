package com.synaltic.cxf.syncope;

import org.apache.cxf.common.security.SimpleGroup;
import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.security.DefaultSecurityContext;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.security.SecurityContext;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.http.Headers;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.syncope.common.to.MembershipTO;
import org.apache.syncope.common.to.UserTO;
import org.apache.wss4j.common.principal.WSUsernameTokenPrincipalImpl;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.message.token.UsernameToken;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.Validator;
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

    private Validator validator;

    private Dictionary properties;

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

            LOGGER.info("Call the Syncope validator");
            try {
                credential = validator.validate(credential, data);
            } catch (Exception e) {
                LOGGER.warn("Syncope authentication failed", e);
                // sendErrorResponse(message, HttpURLConnection.HTTP_FORBIDDEN);
                sendErrorResponse(message, HttpURLConnection.HTTP_UNAUTHORIZED);
            }

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

            // Read the user from Syncope and get the roles
            WebClient client = WebClient.create(address);

            String authorizationHeader = "Basic " + Base64Utility.encode((token.getName() + ":" + token.getPassword()).getBytes());

            client.header("Authorization", authorizationHeader);
            client.accept("application/xml");

            client = client.path("users/self");
            UserTO user = null;
            try {
                user = client.get(UserTO.class);
                if (user == null) {
                    Exception exception = new Exception("Authentication failed");
                    throw new Fault(exception);
                }
            } catch (RuntimeException ex) {
                LOGGER.error(ex.getMessage(), ex);
                throw new Fault(ex);
            }

            // Now get the roles
            List<MembershipTO> membershipList = user.getMemberships();
            LinkedList<String> userRoles = new LinkedList<String>();
            Subject subject = new Subject();
            subject.getPrincipals().add(p);
            for (MembershipTO membership : membershipList) {
                String roleName = membership.getRoleName();
                userRoles.add(roleName);
                subject.getPrincipals().add(new SimpleGroup(roleName, token.getName()));
            }
            subject.setReadOnly();

            // put principal and subject (with the roles) in message DefaultSecurityContext
            message.put(DefaultSecurityContext.class, new DefaultSecurityContext(p, subject));

        } catch (Exception ex) {
            throw new Fault(ex);
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

    public void setValidator(Validator validator) {
        this.validator = validator;
    }

    public void setProperties(Dictionary properties) {
        this.properties = properties;
    }

}
