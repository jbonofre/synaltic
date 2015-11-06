package com.synaltic.cxf.syncope;

import org.apache.cxf.Bus;
import org.apache.cxf.feature.LoggingFeature;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Activator implements BundleActivator {

    private final static Logger LOGGER = LoggerFactory.getLogger(Activator.class);

    private ServiceTracker<Bus, ServiceRegistration> tracker;

    public void start(final BundleContext bundleContext) {
        LOGGER.debug("Starting CXF buses tracker");
        tracker = new ServiceTracker<Bus, ServiceRegistration>(bundleContext, Bus.class, null) {

            public ServiceRegistration<?> addingService(ServiceReference<Bus> reference) {
                Bus bus = bundleContext.getService(reference);
                String id = bus.getId();

                ServiceReference configurationAdminReference = bundleContext.getServiceReference(ConfigurationAdmin.class);
                if (configurationAdminReference == null) {
                    throw new IllegalStateException("ConfigurationAdmin service not found");
                }
                ConfigurationAdmin configurationAdmin = (ConfigurationAdmin) bundleContext.getService(configurationAdminReference);
                if (configurationAdmin == null) {
                    throw new IllegalStateException("ConfigurationAdmin service not found");
                }

                SyncopeValidator syncopeValidator = new SyncopeValidator();
                syncopeValidator.setConfigurationAdmin(configurationAdmin);

                BasicAuthInterceptor syncopeInterceptor = new BasicAuthInterceptor();
                syncopeInterceptor.setValidator(syncopeValidator);
                syncopeInterceptor.setConfigurationAdmin(configurationAdmin);

                InterceptorsUtil util = new InterceptorsUtil(configurationAdmin);
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

                //bundleContext.ungetService(configurationAdminReference);

                return null;
            }

            public void removedService(ServiceReference<Bus> reference, ServiceRegistration reg) {
                reg.unregister();
                super.removedService(reference, reg);
            }


        };
        tracker.open();
    }

    public void stop(BundleContext bundleContext) throws Exception {
        tracker.close();
    }

}
