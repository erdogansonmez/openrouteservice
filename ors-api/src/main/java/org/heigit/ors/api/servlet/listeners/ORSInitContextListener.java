/*
 *  Licensed to GIScience Research Group, Heidelberg University (GIScience)
 *
 *   http://giscience.uni-hd.de
 *   http://heigit.org
 *
 *  under one or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information regarding copyright
 *  ownership. The GIScience licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.heigit.ors.api.servlet.listeners;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.juli.logging.LogFactory;
import org.apache.log4j.Logger;
import org.heigit.ors.api.config.*;
import org.heigit.ors.api.EngineProperties;
import org.heigit.ors.api.services.GraphService;
import org.heigit.ors.api.util.AppInfo;
import org.heigit.ors.config.EngineProperties;
import org.heigit.ors.isochrones.statistics.StatisticsProviderFactory;
import org.heigit.ors.routing.RoutingProfile;
import org.heigit.ors.routing.RoutingProfileManager;
import org.heigit.ors.routing.RoutingProfileManagerStatus;
import org.heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import org.heigit.ors.routing.graphhopper.extensions.manage.ORSGraphManager;
import org.heigit.ors.util.FormatUtility;
import org.heigit.ors.util.StringUtility;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

import static com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN;
import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.*;
import static org.heigit.ors.api.ORSEnvironmentPostProcessor.*;

public class ORSInitContextListener implements ServletContextListener {
    public static final String ORS_API_TESTS_FLAG = "ORS_API_TESTS_FLAG";
    private static final Logger LOGGER = Logger.getLogger(ORSInitContextListener.class);
    private final EndpointsProperties endpointsProperties;
    private final CorsProperties corsProperties;
    private final SystemMessageProperties systemMessageProperties;
    private final LoggingProperties loggingProperties;
    private final ServerProperties serverProperties;
    private final GraphService graphService;
    private final ObjectMapper mapper;

    public ORSInitContextListener(EndpointsProperties endpointsProperties, CorsProperties corsProperties, SystemMessageProperties systemMessageProperties, LoggingProperties loggingProperties, ServerProperties serverProperties, GraphService graphService) {
        this.endpointsProperties = endpointsProperties;
        this.corsProperties = corsProperties;
        this.systemMessageProperties = systemMessageProperties;
        this.loggingProperties = loggingProperties;
        this.serverProperties = serverProperties;
        this.graphService = graphService;
        YAMLFactory yf = new CustomYAMLFactory()
                .disable(WRITE_DOC_START_MARKER)
                .disable(SPLIT_LINES)
                .disable(USE_NATIVE_TYPE_ID)
                .enable(INDENT_ARRAYS_WITH_INDICATOR)
                .enable(MINIMIZE_QUOTES);
        mapper = new ObjectMapper(yf);
        mapper.configure(WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false);
    }

    @Override
    public void contextInitialized(ServletContextEvent contextEvent) {
        String configFileString = loadConfigFileString();
        if (configFileString == null) {
            return;
        }
        EngineProperties engineProperties = loadEngineProperties(configFileString);
        if (engineProperties == null) {
            return;
        }
        String outputTarget = configurationOutputTarget(engineProperties, System.getenv());
        if (!StringUtility.isNullOrEmpty(outputTarget)) {
            writeConfigurationFile(outputTarget, engineProperties);
            return;
        }
        new Thread(() -> {
            try {
                LOGGER.info("Initializing ORS...");
                RoutingProfileManager routingProfileManager = new RoutingProfileManager(engineProperties);
                if (routingProfileManager.getProfiles() != null) {
                    for (RoutingProfile profile : routingProfileManager.getProfiles().getUniqueProfiles()) {
                        ORSGraphHopper orsGraphHopper = profile.getGraphhopper();
                        ORSGraphManager orsGraphManager = orsGraphHopper.getOrsGraphManager();
                        if (orsGraphManager != null && orsGraphManager.useGraphRepository()) {
                            LOGGER.debug("Adding orsGraphManager for profile %s to GraphService".formatted(profile.getConfiguration().getName()));
                            graphService.addGraphhopperLocation(orsGraphManager);
                        }
                    }
                }
                if (Boolean.TRUE.equals(engineProperties.getPreparationMode())) {
                    LOGGER.info("Running in preparation mode, all enabled graphs are built, job is done.");
                    RoutingProfileManagerStatus.setShutdown(true);
                }
            } catch (Exception e) {
                LOGGER.warn("Unable to initialize ORS due to an unexpected exception: " + e);
            }
        }, "ORS-Init").start();
    }

    private String loadConfigFileString() {
        String configFileString = "ors:\n  engine: {}";
        if (!StringUtility.isNullOrEmpty(System.getProperty(ORS_CONFIG_LOCATION_PROPERTY))) {
            try {
                configFileString = new FileSystemResource(System.getProperty(ORS_CONFIG_LOCATION_PROPERTY)).getContentAsString(Charset.defaultCharset());
            } catch (IOException e) {
                LOGGER.error("Failed to read configuration file");
                RoutingProfileManagerStatus.setShutdown(true);
                return null;
            }
        }
        if (!StringUtility.isNullOrEmpty(System.getProperty(ORS_API_TESTS_FLAG))) {
            try {
                configFileString = new ClassPathResource("application-test.yml").getContentAsString(Charset.defaultCharset());
            } catch (IOException e) {
                LOGGER.error("Failed to read configuration file");
                RoutingProfileManagerStatus.setShutdown(true);
                return null;
            }
        }
        return configFileString;
    }

    private EngineProperties loadEngineProperties(String configFileString) {
        EngineProperties engineProperties = null;
        try {
            JsonNode conf = mapper.readTree(configFileString);
            engineProperties = mapper.readValue(conf.get("ors").get("engine").toString(), EngineProperties.class);
            engineProperties.initialize();
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to parse configuration file", e);
            RoutingProfileManagerStatus.setShutdown(true);
        }
        return engineProperties;
    }

    public String configurationOutputTarget(EngineProperties engineProperties, Map<String, String> envMap) {
        String output = engineProperties.getConfigOutput();
        output = envMap.get(ORS_CONFIG_OUTPUT_ENV) != null ? envMap.get(ORS_CONFIG_OUTPUT_ENV) : output;
        output = System.getProperty(ORS_CONFIG_OUTPUT_PROPERTY) != null ? System.getProperty(ORS_CONFIG_OUTPUT_PROPERTY) : output;
        output = envMap.get(ORS_CONFIG_DEFAULT_OUTPUT_ENV) != null ? envMap.get(ORS_CONFIG_DEFAULT_OUTPUT_ENV) : output;
        output = System.getProperty(ORS_CONFIG_DEFAULT_OUTPUT_PROPERTY) != null ? System.getProperty(ORS_CONFIG_DEFAULT_OUTPUT_PROPERTY) : output;
        if (StringUtility.isNullOrEmpty(output))
            return null;
        if (!output.endsWith(".yml") && !output.endsWith(".yaml"))
            output += ".yml";
        return output;
    }

    private void writeConfigurationFile(String output, EngineProperties engineProperties) {
        try (FileOutputStream fos = new FileOutputStream(output); JsonGenerator generator = mapper.createGenerator(fos)) {
            LOGGER.info("Creating configuration file " + output);
            ORSConfigBundle ors = new ORSConfigBundle(corsProperties, systemMessageProperties, endpointsProperties, engineProperties);
            ConfigBundle configBundle = new ConfigBundle(serverProperties, loggingProperties, ors);
            generator.writeObject(configBundle);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Configuration written: \n" + mapper.writeValueAsString(configBundle));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write output configuration file.", e);
        }
        LOGGER.info("Configuration output completed.");
        RoutingProfileManagerStatus.setShutdown(true);
    }

    record ORSConfigBundle(
            @JsonIgnoreProperties({"$$beanFactory"})
            CorsProperties cors,
            @JsonInclude(JsonInclude.Include.CUSTOM)
            @JsonIgnoreProperties({"$$beanFactory"})
            SystemMessageProperties messages,
            @JsonIgnoreProperties({"$$beanFactory"})
            EndpointsProperties endpoints,
            @JsonIgnoreProperties({"$$beanFactory"})
            EngineProperties engine
    ) {
    }

    record ConfigBundle(
            @JsonProperty
            @JsonIgnoreProperties({"$$beanFactory"})
            ServerProperties server,
            @JsonProperty
            @JsonIgnoreProperties({"$$beanFactory"})
            LoggingProperties logging,
            @JsonProperty
            ORSConfigBundle ors
    ) {
    }


    @Override
    public void contextDestroyed(ServletContextEvent contextEvent) {
        try {
            LOGGER.info("Shutting down openrouteservice %s and releasing resources.".formatted(AppInfo.getEngineInfo()));
            FormatUtility.unload();
            if (RoutingProfileManagerStatus.isReady())
                RoutingProfileManager.getInstance().destroy();
            StatisticsProviderFactory.releaseProviders();
            LogFactory.release(Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }
}
