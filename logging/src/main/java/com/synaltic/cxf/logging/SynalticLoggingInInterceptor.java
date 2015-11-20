package com.synaltic.cxf.logging;

import org.apache.cxf.interceptor.LoggingInInterceptor;

import java.util.logging.Logger;

public class SynalticLoggingInInterceptor extends LoggingInInterceptor {

    private Logger logger;

    public SynalticLoggingInInterceptor(String loggerName) {
        logger = Logger.getLogger(loggerName);
        System.out.println("LoggingOutInterceptor loaded (" + loggerName + ")");
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

}
