package org.heigit.ors.api.services;

import org.apache.log4j.Logger;
import org.heigit.ors.api.util.AppInfo;
import org.heigit.ors.config.EngineProperties;
import org.heigit.ors.routing.RoutingProfile;
import org.heigit.ors.routing.RoutingProfileManager;
import org.heigit.ors.routing.graphhopper.extensions.manage.ORSGraphManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GraphService {

    // get this value from ors.engine.graph_management.enabled
    @Value("${ors.engine.graph_management.enabled:false}")
    Boolean enabled = false;

    @Autowired
    EngineProperties engineProperties;

    private static final Logger LOGGER = Logger.getLogger(GraphService.class.getName());

    public List<ORSGraphManager> graphManagers = new ArrayList<>();

    AtomicBoolean graphActivationAttemptWasBlocked = new AtomicBoolean(false);
    AtomicBoolean isActivatingGraphs = new AtomicBoolean(true);

    public void addGraphManagerInstance(ORSGraphManager orsGraphManager) {
        if (orsGraphManager.useGraphRepository()) {
            graphManagers.add(orsGraphManager);
        }
    }
    public void setIsActivatingGraphs(boolean value) {
        isActivatingGraphs.set(value);
    }

    @Async
    @Scheduled(cron = "${ors.engine.graph_management.download_schedule:0 0 0 31 2 *}")//Default is "never"
    public void checkForUpdatesInRepo() {

        if (!enabled) {
            LOGGER.debug("Graph management is disabled, skipping scheduled repository check...");
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("GraphService is currently restarting, skipping scheduled repository check...");
            return;
        }

        LOGGER.debug("Scheduled repository check...");

        if (graphActivationAttemptWasBlocked.get()) {
            LOGGER.warn("Skipping scheduled repository check, waiting for restart...");
            return;
        }
        if (isUpdateLocked()) {
            LOGGER.warn("Scheduled repository check skipped: File %s found - remove lock file manually!".formatted(ORSGraphManager.UPDATE_LOCKFILE_NAME));
            return;
        }

        for (ORSGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.isBusy()) {
                LOGGER.info("[%s] Scheduled repository check: Download or extraction in progress".formatted(orsGraphManager.getQualifiedProfileName()));
            } else {
                LOGGER.info("[%s] Scheduled repository check: Checking for update.".formatted(orsGraphManager.getQualifiedProfileName()));
                orsGraphManager.downloadAndExtractLatestGraphIfNecessary();
            }
        }

        LOGGER.debug("Scheduled repository check done");
    }

    @Async
    @Scheduled(cron = "${ors.engine.graph_management.activation_schedule:0 0 0 31 2 *}")//Default is "never"
    public void checkForDownloadedGraphsToActivate() {
        checkForDownloadedGraphsToActivate("Scheduled");
    }
    public void checkForDownloadedGraphsToActivate(String trigger) {
        if (!enabled) {
            LOGGER.debug("Graph management is disabled, skipping %s activation check...".formatted(trigger.toLowerCase()));
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("Graph activation is in progress, skipping %s scheduled activation check...".formatted(trigger.toLowerCase()));
            return;
        }

        LOGGER.debug("%s graph activation check...".formatted(trigger));

        // Even if restart is locked: Do the checks to start repeatedRestartAttempts.

        boolean restartNeeded = false;
        boolean restartAllowed = true;

        for (ORSGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.isBusy() || orsGraphManager.hasGraphDownloadFile()) {
                if (!graphActivationAttemptWasBlocked.get()) {
                    LOGGER.info("[%s] %s graph activation check: Download or extraction in progress".formatted(
                            trigger,
                            orsGraphManager.getQualifiedProfileName()));
                }
                restartAllowed = false;
            }
            if (orsGraphManager.hasDownloadedExtractedGraph()) {
                if (!graphActivationAttemptWasBlocked.get()) {
                    LOGGER.info("[%s] %s graph activation check: Downloaded extracted graph available".formatted(
                            trigger,
                            orsGraphManager.getQualifiedProfileName()));
                }
                restartNeeded = true;
            }
        }

        if (!restartNeeded) {
            LOGGER.info("%s graph activation check done: No downloaded graphs found, no restart required.".formatted(trigger));
            return;
        }
        if (!restartAllowed) {
            LOGGER.info("%s graph activation check done: Activation currently not allowed, retrying every minute...".formatted(trigger));
            graphActivationAttemptWasBlocked.set(true);
            return;
        }
        if (isActivationLocked()) {
            LOGGER.warn("%s graph activation check done: File %s found - remove lock file manually! Retrying every minute...".formatted(
                    trigger,
                    ORSGraphManager.ACTIVATION_LOCKFILE_NAME));
            graphActivationAttemptWasBlocked.set(true);
            return;
        }

        LOGGER.info("%s graph activation check done: Performing graph activation...".formatted(trigger));
        activateGraphs();
    }

    @Async
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void repeatedGraphActivationAttempt() {
        if (!enabled) {
            LOGGER.debug("Graph management is disabled, skipping repeated attempt to activate graphs...");
            return;
        }
        if (isActivatingGraphs.get()) {
            LOGGER.debug("Graph activation is in progress, skipping repeated attempt to activate graphs...");
            return;
        }
        if (graphActivationAttemptWasBlocked.get()) {
            LOGGER.info("Repeated attempt to activate graphs...");
            checkForDownloadedGraphsToActivate("Repeated");
        }
    }

    private boolean isUpdateLocked() {
        for (ORSGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.hasUpdateLock()) {
                return true;
            }
        }
        return false;
    }

    private boolean isActivationLocked() {
        for (ORSGraphManager orsGraphManager : graphManagers) {
            if (orsGraphManager.hasActivationLock()) {
                return true;
            }
        }
        return false;
    }

    private void activateGraphs() {
        try {
            isActivatingGraphs.set(true);
            graphManagers.clear();
            RoutingProfileManager routingProfileManager = RoutingProfileManager.getInstance();
            routingProfileManager.initialize(engineProperties, AppInfo.GRAPH_VERSION);
            for (RoutingProfile profile : routingProfileManager.getUniqueProfiles()) {
                ORSGraphManager orsGraphManager = profile.getGraphhopper().getOrsGraphManager();
                if (orsGraphManager != null && orsGraphManager.useGraphRepository()) {
                    LOGGER.debug("[%s] Adding orsGraphManager for profile %s with encoder %s to GraphService".formatted(
                            orsGraphManager.getQualifiedProfileName(),
                            orsGraphManager.getQualifiedProfileName(),
                            profile.getProfileConfiguration().getEncoderName()));
                    addGraphManagerInstance(orsGraphManager);
                }
            }
        }
        catch (Exception e) {
            LOGGER.warn("Unable to activate graphs due to an unexpected exception: " + e);
        }
        finally {
            isActivatingGraphs.set(false);
            graphActivationAttemptWasBlocked.set(false);
        }
    }
}
