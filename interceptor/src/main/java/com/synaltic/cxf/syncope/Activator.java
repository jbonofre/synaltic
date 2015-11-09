package com.synaltic.cxf.syncope;

import org.apache.cxf.Bus;
import org.apache.cxf.feature.LoggingFeature;
import org.apache.cxf.interceptor.Interceptor;
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

    private final static String CONFIG_PID = "com.synaltic.cxf.syncope.authorization";

    private ServiceTracker<Bus, ServiceRegistration> cxfBusesTracker;
    private ServiceRegistration managedServiceRegistration;
    private Dictionary properties;

    public void start(final BundleContext bundleContext) throws Exception {
        LOGGER.debug("Starting CXF buses cxfBusesTracker");
        cxfBusesTracker = new ServiceTracker<Bus, ServiceRegistration>(bundleContext, Bus.class, null) {

            public ServiceRegistration<?> addingService(ServiceReference<Bus> reference) {
                Bus bus = bundleContext.getService(reference);
                String id = bus.getId();

                SyncopeValidator syncopeValidator = new SyncopeValidator();
                syncopeValidator.setProperties(properties);

                SyncopeInterceptor syncopeInterceptor = new SyncopeInterceptor();
                syncopeInterceptor.setValidator(syncopeValidator);
                syncopeInterceptor.setProperties(properties);

                InterceptorsUtil util = new InterceptorsUtil(properties);
                try {
                    if (util.busDefined(id)) {
                        LOGGER.debug("Injecting Syncope interceptor on CXF bus {}", id);
                        bus.getFeatures().add(new LoggingFeature());
                        if (!bus.getInInterceptors().contains(syncopeInterceptor)) {
                            bus.getInInterceptors().add(syncopeInterceptor);
                            LOGGER.info("Syncope interceptor injected on CXF bus {}, id");
                        } else {
                            LOGGER.debug("Syncope interceptor already defined on CXF bus {}", id);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("CXF bus tracking error", e);
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
            // update existing buses
            try {
                ServiceReference[] references = bundleContext.getServiceReferences(Bus.class.getName(), null);
                for (ServiceReference reference : references) {
                    Bus bus = (Bus) bundleContext.getService(reference);
                    String id = bus.getId();
                    InterceptorsUtil util = new InterceptorsUtil(properties);
                    if (!util.busDefined(id)) {
                        for (Interceptor interceptor : bus.getInInterceptors()) {
                            if ((interceptor instanceof SyncopeInterceptor)) {
                                bus.getInInterceptors().remove(interceptor);
                            }
                        }
                    } else {
                        boolean found = false;
                        for (Interceptor interceptor : bus.getInInterceptors()) {
                            if ((interceptor instanceof SyncopeInterceptor)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            SyncopeValidator syncopeValidator = new SyncopeValidator();
                            syncopeValidator.setProperties(properties);

                            SyncopeInterceptor syncopeInterceptor = new SyncopeInterceptor();
                            syncopeInterceptor.setValidator(syncopeValidator);
                            syncopeInterceptor.setProperties(properties);

                            bus.getInInterceptors().add(syncopeInterceptor);
                        }
                    }
                }
            } catch (Exception e) {
                throw new ConfigurationException("", "Can't update configuration", e);
            }
        }
    }

}
