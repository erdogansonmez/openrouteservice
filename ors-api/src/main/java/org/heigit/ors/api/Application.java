package org.heigit.ors.api;

import jakarta.servlet.ServletContextListener;
import org.apache.log4j.Logger;
import org.heigit.ors.api.config.EngineProperties;
import org.heigit.ors.api.services.GraphService;
import org.heigit.ors.api.servlet.listeners.ORSInitContextListener;
import org.heigit.ors.api.util.AppInfo;
import org.heigit.ors.routing.RoutingProfileManagerStatus;
import org.heigit.ors.util.StringUtility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Optional;

@ServletComponentScan("org.heigit.ors.api.servlet.listeners")
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class Application extends SpringBootServletInitializer {
    private static final Logger LOG = Logger.getLogger(Application.class.getName());

    static {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    }

    @Autowired
    ConfigurableApplicationContext context;

    public static void main(String[] args) {
        if (args.length > 0 && !StringUtility.isNullOrEmpty(args[0]) && !args[0].startsWith("-")) {
            System.setProperty(ORSEnvironmentPostProcessor.ORS_CONFIG_LOCATION_PROPERTY, args[0]);
        }
        SpringApplication.run(Application.class, args);
        LOG.info("openrouteservice %s".formatted(AppInfo.getEngineInfo()));
        if (RoutingProfileManagerStatus.isShutdown()) {
            System.exit(RoutingProfileManagerStatus.hasFailed() ? 1 : 0);
        }
    }

    public void restart() {
        String[] args = Optional.ofNullable(context)
                .map(ctx -> ctx.getBean(ApplicationArguments.class))
                .map(ApplicationArguments::getSourceArgs)
                .orElse(new String[0]);

        Thread thread = new Thread(() -> {
            context.close();
            LOG.debug("Restart with args [%s]".formatted(String.join(", ", args)));
            context = SpringApplication.run(Application.class, args);
        });

        thread.setDaemon(false);
        thread.start();
    }

    @Bean("orsInitContextListenerBean")
    public ServletListenerRegistrationBean<ServletContextListener> createORSInitContextListenerBean(EngineProperties engineProperties, GraphService graphService) {
        ServletListenerRegistrationBean<ServletContextListener> bean = new ServletListenerRegistrationBean<>();
        bean.setListener(new ORSInitContextListener(engineProperties, graphService));
        return bean;
    }
}
