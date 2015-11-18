package com.synaltic.cxf.logging;

import org.apache.cxf.interceptor.LoggingOutInterceptor;

import java.util.logging.Logger;

public class SynalticLoggingOutInterceptor extends LoggingOutInterceptor {

    private Logger logger;

    public SynalticLoggingOutInterceptor(String loggerName) {
        logger = Logger.getLogger(loggerName);
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

}
