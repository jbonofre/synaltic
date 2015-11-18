package com.synaltic.cxf.logging;

import org.apache.cxf.Bus;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.feature.LoggingFeature;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.osgi.framework.*;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Hashtable;

public class Activator implements BundleActivator {

    private final static Logger LOGGER = LoggerFactory.getLogger(Activator.class);

    private final static String CONFIG_PID = "com.synaltic.cxf.logging";

    private ServiceTracker<Bus, ServiceRegistration> cxfBusesTracker;
    private ServiceRegistration managedServiceRegistration;
    private Dictionary properties;

    private void inject(Bus bus, Dictionary properties) throws Exception {
        InterceptorsUtil util = new InterceptorsUtil(properties);
        if (util.isLoggingEnabled(bus.getId())) {
            LoggingInInterceptor inInterceptor = new LoggingInInterceptor();
            inInterceptor.setPrettyLogging(true);
            LoggingOutInterceptor outInterceptor = new LoggingOutInterceptor();
            outInterceptor.setPrettyLogging(true);
            LOGGER.debug("Inject logging interceptors in bus {}", bus.getId());
            bus.getInInterceptors().add(inInterceptor);
            bus.getOutInterceptors().add(outInterceptor);
            bus.getOutFaultInterceptors().add(outInterceptor);
        }
    }

    private void remove(Bus bus) {
        for (Interceptor interceptor : bus.getInInterceptors()) {
            if (interceptor instanceof LoggingInInterceptor) {
                bus.getInInterceptors().remove(interceptor);
            }
        }
        for (Interceptor interceptor : bus.getOutInterceptors()) {
            if (interceptor instanceof LoggingOutInterceptor) {
                bus.getOutInterceptors().remove(interceptor);
            }
        }
        for (Interceptor interceptor : bus.getOutFaultInterceptors()) {
            if (interceptor instanceof LoggingOutInterceptor) {
                bus.getOutFaultInterceptors().remove(interceptor);
            }
        }
    }

    public void start(final BundleContext bundleContext) throws Exception {
        System.setProperty("org.apache.cxf.Logger", "org.apache.cxf.common.logging.Slf4jLogger");
        LOGGER.debug("Starting CXF buses cxfBusesTracker");
        cxfBusesTracker = new ServiceTracker<Bus, ServiceRegistration>(bundleContext, Bus.class, null) {

            public ServiceRegistration<?> addingService(ServiceReference<Bus> reference) {
                Bus bus = bundleContext.getService(reference);

                try {
                    inject(bus, properties);
                } catch (Exception e) {
                    LOGGER.error("Can't inject Syncope interceptor", e);
                }

                return null;
            }

            public void removedService(ServiceReference<Bus> reference, ServiceRegistration reg) {
                reg.unregister();
                super.removedService(reference, reg);
            }


        };
        cxfBusesTracker.open();
        Dictionary<String, String> properties = new Hashtable<String, String>();
        properties.put(Constants.SERVICE_PID, CONFIG_PID);
        managedServiceRegistration = bundleContext.registerService(ManagedService.class.getName(), new ConfigUpdater(bundleContext), properties);
    }

    public void stop(BundleContext bundleContext) throws Exception {
        if (cxfBusesTracker != null)
            cxfBusesTracker.close();
        if (managedServiceRegistration != null)
            managedServiceRegistration.unregister();
    }

    private final class ConfigUpdater implements ManagedService {

        private BundleContext bundleContext;

        public ConfigUpdater(BundleContext bundleContext) {
            this.bundleContext = bundleContext;
        }

        public void updated(Dictionary<String, ?> config) throws ConfigurationException {
            properties = config;
            try {
                ServiceReference[] references = bundleContext.getServiceReferences(Bus.class.getName(), null);
                for (ServiceReference reference : references) {
                    Bus bus = (Bus) bundleContext.getService(reference);

                    InterceptorsUtil util = new InterceptorsUtil(properties);
                    remove(bus);
                    if (util.isLoggingEnabled(bus.getId())) {
                        inject(bus, properties);
                    }
                }
            } catch (Exception e) {
                throw new ConfigurationException("", "Can't update configuration", e);
            }
        }
    }

}
