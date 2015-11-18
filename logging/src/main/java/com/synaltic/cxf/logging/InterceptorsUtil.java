package com.synaltic.cxf.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utils to get the CXF buses defined in the configuration
 */
public class InterceptorsUtil {

    private Dictionary properties;

    private final static Logger LOGGER = LoggerFactory.getLogger(InterceptorsUtil.class);

    public InterceptorsUtil(Dictionary properties) {
        this.properties = properties;
    }

    /**
     * Get the buses defined in the configuration.
     *
     * @return the list of bus ID defined
     */
    public List<String> getBuses() throws Exception {
        ArrayList<String> buses = new ArrayList<String>();
        if (properties != null) {
            Enumeration keys = properties.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                LOGGER.debug("Adding CXF bus {}", key);
                buses.add(key);
            }
        }
        return buses;
    }

    /**
     * Check if a bus ID is defined in the configuration
     *
     * @param id the CXF bus ID to check.
     * @return true if the bus is defined in the configuration and logging enabled, false else.
     */
    public String getLogger(String id) throws Exception {
        List<String> buses = this.getBuses();
        for (String bus : buses) {
            Pattern pattern = Pattern.compile(bus);
            Matcher matcher = pattern.matcher(id);
            if (matcher.matches()) {
                String logger = (String) properties.get(bus);
                return logger;
            }
        }
        return null;
    }

}