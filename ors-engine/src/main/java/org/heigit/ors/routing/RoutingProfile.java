/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.ConditionalEdges;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import org.apache.log4j.Logger;
import org.heigit.ors.config.ElevationProperties;
import org.heigit.ors.config.EngineProperties;
import org.heigit.ors.config.profile.ExecutionProperties;
import org.heigit.ors.config.profile.PreparationProperties;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.exceptions.InternalServerException;
import org.heigit.ors.isochrones.*;
import org.heigit.ors.isochrones.statistics.StatisticsProvider;
import org.heigit.ors.isochrones.statistics.StatisticsProviderConfiguration;
import org.heigit.ors.isochrones.statistics.StatisticsProviderFactory;
import org.heigit.ors.routing.graphhopper.extensions.*;
import org.heigit.ors.routing.graphhopper.extensions.flagencoders.FlagEncoderNames;
import org.heigit.ors.routing.graphhopper.extensions.storages.builders.BordersGraphStorageBuilder;
import org.heigit.ors.routing.graphhopper.extensions.storages.builders.GraphStorageBuilder;
import org.heigit.ors.routing.graphhopper.extensions.util.ORSParameters;
import org.heigit.ors.routing.parameters.ProfileParameters;
import org.heigit.ors.routing.pathprocessors.ORSPathProcessorFactory;
import org.heigit.ors.util.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * This class generates {@link RoutingProfile} classes and is used by mostly all service classes e.g.
 * <p>
 * {@link RoutingProfileManager} etc.
 *
 * @author Openrouteserviceteam
 * @author Julian Psotta, julian@openrouteservice.org
 */
public class RoutingProfile {
    private static final Logger LOGGER = Logger.getLogger(RoutingProfile.class);
    private static final Object lockObj = new Object();
    private static int profileIdentifier = 0;
    private final Integer[] mRoutePrefs;
    private final String name;
    private final ProfileProperties profile;
    private final ORSGraphHopper mGraphHopper;
    private String astarApproximation;
    private Double astarEpsilon;

    public RoutingProfile(String name, ProfileProperties profile, EngineProperties engineConfig, RoutingProfileLoadContext loadCntx) throws Exception {
        this.name = name;
        this.profile = profile;
        mRoutePrefs = profile.getProfilesTypes();
        mGraphHopper = initGraphHopper(name, profile, engineConfig, loadCntx);
        ExecutionProperties execution = profile.getExecution();
        if (execution.getMethods().getAstar().getApproximation() != null)
            astarApproximation = execution.getMethods().getAstar().getApproximation();
        if (execution.getMethods().getAstar().getEpsilon() != null)
            astarEpsilon = execution.getMethods().getAstar().getEpsilon();
    }

    public static ORSGraphHopper initGraphHopper(String profileName, ProfileProperties profile, EngineProperties engineConfig, RoutingProfileLoadContext loadCntx) throws Exception {
        ORSGraphHopperConfig args = createGHSettings(profile, engineConfig);

        int profileId;
        synchronized (lockObj) {
            profileIdentifier++;
            profileId = profileIdentifier;
        }

        long startTime = System.currentTimeMillis();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[%d] Profiles: '%s', location: '%s'.".formatted(profileId, profile.getEncoderName().toString(), profile.getGraphPath()));
        }

        GraphProcessContext gpc = new GraphProcessContext(profile);
        gpc.setGetElevationFromPreprocessedData(engineConfig.getElevation().getPreprocessed());

        ORSGraphHopper gh = new ORSGraphHopper(gpc, engineConfig);
        gh.setRouteProfileName(profileName);
        ORSDefaultFlagEncoderFactory flagEncoderFactory = new ORSDefaultFlagEncoderFactory();
        gh.setFlagEncoderFactory(flagEncoderFactory);

        ORSPathProcessorFactory pathProcessorFactory = new ORSPathProcessorFactory();
        gh.setPathProcessorFactory(pathProcessorFactory);

        gh.init(args);

        // MARQ24: make sure that we only use ONE instance of the ElevationProvider across the multiple vehicle profiles
        // so the caching for elevation data will/can be reused across different vehicles. [the loadCntx is a single
        // Object that will shared across the (potential) multiple running instances]
        if (loadCntx.getElevationProvider() != null) {
            if (args.has("graph.elevation.provider")) {
                gh.setElevationProvider(loadCntx.getElevationProvider());
            }
        } else {
            loadCntx.setElevationProvider(gh.getElevationProvider());
        }
        gh.setGraphStorageFactory(new ORSGraphStorageFactory(gpc.getStorageBuilders()));

        gh.initializeGraphManagement();

        gh.importOrLoad();
        // store CountryBordersReader for later use
        for (GraphStorageBuilder builder : gpc.getStorageBuilders()) {
            if (builder.getName().equals(BordersGraphStorageBuilder.BUILDER_NAME)) {
                pathProcessorFactory.setCountryBordersReader(((BordersGraphStorageBuilder) builder).getCbReader());
            }
        }

        if (LOGGER.isInfoEnabled()) {
            GraphHopperStorage ghStorage = gh.getGraphHopperStorage();
            LOGGER.info("[%d] Edges: %s - Nodes: %s.".formatted(profileId, ghStorage.getEdges(), ghStorage.getNodes()));
            LOGGER.info("[%d] Total time: %s.".formatted(profileId, TimeUtility.getElapsedTime(startTime, true)));
            LOGGER.info("[%d] Finished at: %s.".formatted(profileId, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())));
        }

        // Make a stamp which help tracking any changes in the size of OSM file.
        File file = new File(engineConfig.getSourceFile().toAbsolutePath().toString());
        Path pathTimestamp = Paths.get(profile.getGraphPath().toString(), "stamp.txt");
        File file2 = pathTimestamp.toFile();
        if (!file2.exists())
            Files.write(pathTimestamp, Long.toString(file.length()).getBytes());

        return gh;
    }

    private static ORSGraphHopperConfig createGHSettings(ProfileProperties profile, EngineProperties engineConfig) {
        ORSGraphHopperConfig ghConfig = new ORSGraphHopperConfig();
        ghConfig.putObject("graph.dataaccess", engineConfig.getGraphsDataAccess());
        ghConfig.putObject("datareader.file", engineConfig.getSourceFile().toAbsolutePath().toString());
        ghConfig.putObject("graph.location", profile.getGraphPath().toString());
        ghConfig.putObject("graph.bytes_for_flags", profile.getEncoderFlagsSize());

        if (Boolean.FALSE.equals(profile.getInstructions())) {
            ghConfig.putObject("instructions", false);
        }

        ElevationProperties elevationProps = engineConfig.getElevation();
        if (elevationProps.getProvider() != null && elevationProps.getCachePath() != null) {
            ghConfig.putObject("graph.elevation.provider", StringUtility.trimQuotes(elevationProps.getProvider()));
            ghConfig.putObject("graph.elevation.cache_dir", StringUtility.trimQuotes(elevationProps.getCachePath().toString()));
            // TODO check
            ghConfig.putObject("graph.elevation.dataaccess", StringUtility.trimQuotes(elevationProps.getDataAccess().toString()));
            ghConfig.putObject("graph.elevation.clear", elevationProps.getCacheClear());
            if (Boolean.TRUE.equals(profile.getInterpolateBridgesAndTunnels()))
                ghConfig.putObject("graph.encoded_values", "road_environment");
            if (Boolean.TRUE.equals(profile.getElevationSmoothing()))
                ghConfig.putObject("graph.elevation.smoothing", true);
        }

        boolean prepareCH = false;
        boolean prepareLM = false;
        boolean prepareCore = false;
        boolean prepareFI = false;

        Integer[] profilesTypes = profile.getProfilesTypes();
        Map<String, Profile> profiles = new LinkedHashMap<>();

        // TODO Future improvement : Multiple profiles were used to share the graph  for several
        //       bike profiles. We don't use this feature now but it might be
        //       desireable in the future. However, this behavior is standard
        //       in original GH through an already existing mechanism.
        if (profilesTypes.length != 1)
            throw new IllegalStateException("Expected single profile in config");

        String vehicle = RoutingProfileType.getEncoderName(profilesTypes[0]);

        boolean hasTurnCosts = Boolean.TRUE.equals(profile.getEncoderOptions().getTurnCosts());

        // TODO Future improvement : make this list of weightings configurable for each vehicle as in GH
        String[] weightings = {ProfileTools.VAL_FASTEST, ProfileTools.VAL_SHORTEST, ProfileTools.VAL_RECOMMENDED};
        for (String weighting : weightings) {
            if (hasTurnCosts) {
                String profileName = ProfileTools.makeProfileName(vehicle, weighting, true);
                profiles.put(profileName, new Profile(profileName).setVehicle(vehicle).setWeighting(weighting).setTurnCosts(true));
            }
            String profileName = ProfileTools.makeProfileName(vehicle, weighting, false);
            profiles.put(profileName, new Profile(profileName).setVehicle(vehicle).setWeighting(weighting).setTurnCosts(false));
        }

        ghConfig.putObject(ProfileTools.KEY_PREPARE_CORE_WEIGHTINGS, "no");
        if (profile.getPreparation() != null) {
            PreparationProperties preparations = profile.getPreparation();


            if (preparations.getMinNetworkSize() != null)
                ghConfig.putObject("prepare.min_network_size", preparations.getMinNetworkSize());

            if (!preparations.getMethods().isEmpty()) {
                if (!preparations.getMethods().getCh().isEmpty()) {
                    PreparationProperties.MethodsProperties.CHProperties chOpts = preparations.getMethods().getCh();
                    prepareCH = chOpts.isEnabled() == null || chOpts.isEnabled();
                    if (prepareCH) {
                        if (chOpts.getThreadsSave() != null)
                            ghConfig.putObject("prepare.ch.threads", chOpts.getThreadsSave());
                        if (chOpts.getWeightings() != null) {
                            List<CHProfile> chProfiles = new ArrayList<>();
                            String chWeightingsString = StringUtility.trimQuotes(chOpts.getWeightings());
                            for (String weighting : chWeightingsString.split(","))
                                chProfiles.add(new CHProfile(ProfileTools.makeProfileName(vehicle, weighting, false)));
                            ghConfig.setCHProfiles(chProfiles);
                        }
                    }
                }

                if (!preparations.getMethods().getLm().isEmpty()) {
                    PreparationProperties.MethodsProperties.LMProperties lmOpts = preparations.getMethods().getLm();
                    prepareLM = lmOpts.isEnabled() == null || lmOpts.isEnabled();
                    if (prepareLM) {
                        if (lmOpts.getThreadsSave() != null)
                            ghConfig.putObject("prepare.lm.threads", lmOpts.getThreadsSave());
                        if (lmOpts.getWeightings() != null) {
                            List<LMProfile> lmProfiles = new ArrayList<>();
                            String lmWeightingsString = StringUtility.trimQuotes(lmOpts.getWeightings());
                            for (String weighting : lmWeightingsString.split(","))
                                lmProfiles.add(new LMProfile(ProfileTools.makeProfileName(vehicle, weighting, hasTurnCosts)));
                            ghConfig.setLMProfiles(lmProfiles);
                        }
                        if (lmOpts.getLandmarks() != null)
                            ghConfig.putObject("prepare.lm.landmarks", lmOpts.getLandmarks());
                    }
                }

                if (!preparations.getMethods().getCore().isEmpty()) {
                    PreparationProperties.MethodsProperties.CoreProperties coreOpts = preparations.getMethods().getCore();
                    prepareCore = coreOpts.isEnabled() == null || coreOpts.isEnabled();
                    if (prepareCore) {
                        if (coreOpts.getThreadsSave() != null) {
                            // TODO check with taki
                            Integer threadsCore = coreOpts.getThreadsSave();
                            ghConfig.putObject("prepare.core.threads", threadsCore);
                            ghConfig.putObject("prepare.corelm.threads", threadsCore);
                        }
                        if (coreOpts.getWeightings() != null) {
                            List<CHProfile> coreProfiles = new ArrayList<>();
                            List<LMProfile> coreLMProfiles = new ArrayList<>();
                            String coreWeightingsString = StringUtility.trimQuotes(coreOpts.getWeightings());
                            for (String weighting : coreWeightingsString.split(",")) {
                                String configStr = "";
                                if (weighting.contains("|")) {
                                    configStr = weighting;
                                    weighting = weighting.split("\\|")[0];
                                }
                                PMap configMap = new PMap(configStr);
                                boolean considerTurnRestrictions = configMap.getBool("edge_based", hasTurnCosts);

                                String profileName = ProfileTools.makeProfileName(vehicle, weighting, considerTurnRestrictions);
                                profiles.put(profileName, new Profile(profileName).setVehicle(vehicle).setWeighting(weighting).setTurnCosts(considerTurnRestrictions));
                                coreProfiles.add(new CHProfile(profileName));
                                coreLMProfiles.add(new LMProfile(profileName));
                            }
                            ghConfig.setCoreProfiles(coreProfiles);
                            ghConfig.setCoreLMProfiles(coreLMProfiles);
                        }
                        if (coreOpts.getLmsets() != null)
                            ghConfig.putObject("prepare.corelm.lmsets", StringUtility.trimQuotes(coreOpts.getLmsets()));
                        if (coreOpts.getLandmarks() != null)
                            ghConfig.putObject("prepare.corelm.landmarks", coreOpts.getLandmarks());
                    } else {
                        ghConfig.putObject(ProfileTools.KEY_PREPARE_CORE_WEIGHTINGS, "no");
                    }
                }

                if (!preparations.getMethods().getFastisochrones().isEmpty()) {
                    PreparationProperties.MethodsProperties.FastIsochroneProperties fastisochroneOpts = preparations.getMethods().getFastisochrones();
                    prepareFI = fastisochroneOpts.isEnabled() == null || fastisochroneOpts.isEnabled();
                    if (prepareFI) {
                        ghConfig.putObject(ORSParameters.FastIsochrone.PROFILE, profile.getEncoderName().toString());
                        //Copied from core
                        if (fastisochroneOpts.getThreadsSave() != null)
                            ghConfig.putObject("prepare.fastisochrone.threads", fastisochroneOpts.getThreadsSave());
                        if (fastisochroneOpts.getMaxcellnodes() != null)
                            ghConfig.putObject("prepare.fastisochrone.maxcellnodes", fastisochroneOpts.getMaxcellnodes());
                        if (fastisochroneOpts.getWeightings() != null) {
                            List<Profile> fastisochronesProfiles = new ArrayList<>();
                            String fastisochronesWeightingsString = StringUtility.trimQuotes(fastisochroneOpts.getWeightings());
                            for (String weighting : fastisochronesWeightingsString.split(",")) {
                                String configStr = "";
                                weighting = weighting.trim();
                                if (weighting.contains("|")) {
                                    configStr = weighting;
                                    weighting = weighting.split("\\|")[0];
                                }
                                PMap configMap = new PMap(configStr);
                                boolean considerTurnRestrictions = configMap.getBool("edge_based", hasTurnCosts);

                                String profileName = ProfileTools.makeProfileName(vehicle, weighting, considerTurnRestrictions);
                                Profile ghProfile = new Profile(profileName).setVehicle(vehicle).setWeighting(weighting).setTurnCosts(considerTurnRestrictions);
                                profiles.put(profileName, ghProfile);
                                fastisochronesProfiles.add(ghProfile);
                            }
                            ghConfig.setFastisochroneProfiles(fastisochronesProfiles);
                        }
                    } else {
                        ghConfig.putObject(ProfileTools.KEY_PREPARE_FASTISOCHRONE_WEIGHTINGS, "no");
                    }
                }
            }
        }

        if (profile.getExecution() != null) {
            ExecutionProperties execution = profile.getExecution();
            if (!execution.getMethods().getCore().isEmpty()) {
                if (execution.getMethods().getCore().getActiveLandmarks() != null)
                    ghConfig.putObject("routing.corelm.active_landmarks", execution.getMethods().getCore().getActiveLandmarks());
            }
            if (!execution.getMethods().getLm().isEmpty()) {
                if (execution.getMethods().getLm().getActiveLandmarks() != null)
                    ghConfig.putObject("routing.lm.active_landmarks", execution.getMethods().getLm().getActiveLandmarks());
            }
        }

        if (profile.getOptimize() && !prepareCH)
            ghConfig.putObject("graph.do_sort", true);

        // Check if getGTFSFile exists
        if (profile.getGtfsFile() != null && !profile.getGtfsFile().toString().isEmpty())
            ghConfig.putObject("gtfs.file", profile.getGtfsFile().toString());

        String flagEncoder = vehicle;
        if (!Helper.isEmpty(profile.getEncoderOptionsString()))
            flagEncoder += "|" + profile.getEncoderOptionsString();

        ghConfig.putObject("graph.flag_encoders", flagEncoder.toLowerCase());
        ghConfig.putObject("index.high_resolution", profile.getLocationIndexResolution());
        ghConfig.putObject("index.max_region_search", profile.getLocationIndexSearchIterations());
        ghConfig.putObject("ext_storages", profile.getExtStorages());
        ghConfig.setProfiles(new ArrayList<>(profiles.values()));

        return ghConfig;
    }

    public boolean hasCHProfile(String profileName) {
        boolean hasCHProfile = false;
        for (CHProfile chProfile : getGraphhopper().getCHPreparationHandler().getCHProfiles()) {
            if (profileName.equals(chProfile.getProfile()))
                hasCHProfile = true;
        }
        return hasCHProfile;
    }

    private boolean hasCoreProfile(String profileName) {
        boolean hasCoreProfile = false;
        for (CHProfile chProfile : getGraphhopper().getCorePreparationHandler().getCHProfiles()) {
            if (profileName.equals(chProfile.getProfile()))
                hasCoreProfile = true;
        }
        return hasCoreProfile;
    }

    public long getMemoryUsage() {
        return mGraphHopper.getMemoryUsage();
    }

    public ORSGraphHopper getGraphhopper() {
        return mGraphHopper;
    }

    public BBox getBounds() {
        return mGraphHopper.getGraphHopperStorage().getBounds();
    }

    public StorableProperties getGraphProperties() {
        return mGraphHopper.getGraphHopperStorage().getProperties();
    }

    public ProfileProperties getProfileConfiguration() {
        return profile;
    }

    public Integer[] getPreferences() {
        return mRoutePrefs;
    }

    public boolean isCHEnabled() {
        return mGraphHopper != null && mGraphHopper.getCHPreparationHandler().isEnabled();
    }

    public void close() {
        mGraphHopper.close();
    }

    public String getAstarApproximation() {
        return astarApproximation;
    }

    public Double getAstarEpsilon() {
        return astarEpsilon;
    }


    /**
     * This function creates the actual {@link IsochroneMap}.
     * It is important, that whenever attributes contains pop_total it must also contain pop_area. If not the data won't be complete.
     * So the first step in the function is a checkup on that.
     *
     * @param parameters The input are {@link IsochroneSearchParameters}
     * @param attributes The input are a {@link String}[] holding the attributes if set
     * @return The return will be an {@link IsochroneMap}
     * @throws Exception
     */
    public IsochroneMap buildIsochrone(IsochroneSearchParameters parameters, String[] attributes) throws Exception {
        // Checkup for pop_total. If the value is set, pop_area must always be set here, if not already done so by the user.
        String[] tempAttributes;
        if (Arrays.toString(attributes).contains(ProfileTools.KEY_TOTAL_POP.toLowerCase()) && !(Arrays.toString(attributes).contains(ProfileTools.KEY_TOTAL_AREA_KM.toLowerCase()))) {
            tempAttributes = new String[attributes.length + 1];
            int i = 0;
            while (i < attributes.length) {
                String attribute = attributes[i];
                tempAttributes[i] = attribute;
                i++;
            }
            tempAttributes[i] = ProfileTools.KEY_TOTAL_AREA_KM;
        } else if ((Arrays.toString(attributes).contains(ProfileTools.KEY_TOTAL_AREA_KM.toLowerCase())) && (!Arrays.toString(attributes).contains(ProfileTools.KEY_TOTAL_POP.toLowerCase()))) {
            tempAttributes = new String[attributes.length + 1];
            int i = 0;
            while (i < attributes.length) {
                String attribute = attributes[i];
                tempAttributes[i] = attribute;
                i++;
            }
            tempAttributes[i] = ProfileTools.KEY_TOTAL_POP;
        } else {
            tempAttributes = attributes;
        }


        IsochroneMap result;

        try {
            RouteSearchContext searchCntx = createSearchContext(parameters.getRouteParameters());

            IsochroneMapBuilderFactory isochroneMapBuilderFactory = new IsochroneMapBuilderFactory(searchCntx);
            result = isochroneMapBuilderFactory.buildMap(parameters);

        } catch (Exception ex) {
            if (DebugUtility.isDebug()) {
                LOGGER.error(ex);
            }
            throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to build an isochrone map.");
        }

        if (tempAttributes != null && result.getIsochronesCount() > 0) {
            try {
                Map<StatisticsProviderConfiguration, List<String>> mapProviderToAttrs = new HashMap<>();
                for (String attr : tempAttributes) {
                    StatisticsProviderConfiguration provConfig = parameters.getStatsProviders().get(attr);

                    if (provConfig != null) {
                        if (mapProviderToAttrs.containsKey(provConfig)) {
                            List<String> attrList = mapProviderToAttrs.get(provConfig);
                            attrList.add(attr);
                        } else {
                            List<String> attrList = new ArrayList<>();
                            attrList.add(attr);
                            mapProviderToAttrs.put(provConfig, attrList);
                        }
                    }
                }

                for (Map.Entry<StatisticsProviderConfiguration, List<String>> entry : mapProviderToAttrs.entrySet()) {
                    StatisticsProviderConfiguration provConfig = entry.getKey();
                    StatisticsProvider provider = StatisticsProviderFactory.getProvider(provConfig.getName(), provConfig.getParameters());
                    String[] provAttrs = provConfig.getMappedProperties(entry.getValue());

                    for (Isochrone isochrone : result.getIsochrones()) {
                        double[] attrValues = provider.getStatistics(isochrone, provAttrs);
                        isochrone.setAttributes(entry.getValue(), attrValues, provConfig.getAttribution());
                    }
                }

            } catch (Exception ex) {
                if (DebugUtility.isDebug()) {
                    LOGGER.error(ex);
                }
                throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to compute isochrone attributes.");
            }
        }

        return result;
    }

    public RouteSearchContext createSearchContext(RouteSearchParameters searchParams) throws Exception {
        PMap props = new PMap();

        int profileType = searchParams.getProfileType();
        String encoderName = RoutingProfileType.getEncoderName(profileType);

        if (FlagEncoderNames.UNKNOWN.equals(encoderName))
            throw new InternalServerException(RoutingErrorCodes.UNKNOWN, "unknown vehicle profile.");

        if (!mGraphHopper.getEncodingManager().hasEncoder(encoderName)) {
            throw new IllegalArgumentException("Vehicle " + encoderName + " unsupported. " + "Supported are: "
                    + mGraphHopper.getEncodingManager());
        }

        FlagEncoder flagEncoder = mGraphHopper.getEncodingManager().getEncoder(encoderName);
        ProfileParameters profileParams = searchParams.getProfileParameters();

        // PARAMETERS FOR PathProcessorFactory

        props.putObject("routing_extra_info", searchParams.getExtraInfo());
        props.putObject("routing_suppress_warnings", searchParams.getSuppressWarnings());

        props.putObject("routing_profile_type", profileType);
        props.putObject("routing_profile_params", profileParams);

        /*
         * PARAMETERS FOR EdgeFilterFactory
         * ======================================================================================================
         */

        /* Avoid areas */
        if (searchParams.hasAvoidAreas()) {
            props.putObject("avoid_areas", searchParams.getAvoidAreas());
        }

        /* Heavy vehicle filter */
        if (profileType == RoutingProfileType.DRIVING_HGV) {
            props.putObject("edgefilter_hgv", searchParams.getVehicleType());
        }

        /* Wheelchair filter */
        else if (profileType == RoutingProfileType.WHEELCHAIR) {
            props.putObject("edgefilter_wheelchair", "true");
        }

        /* Avoid features */
        if (searchParams.hasAvoidFeatures()) {
            props.putObject("avoid_features", searchParams);
        }

        /* Avoid borders of some form */
        if ((searchParams.hasAvoidBorders() || searchParams.hasAvoidCountries())
                && (RoutingProfileType.isDriving(profileType) || RoutingProfileType.isCycling(profileType))) {
            props.putObject("avoid_borders", searchParams);
            if (searchParams.hasAvoidCountries())
                props.putObject("avoid_countries", Arrays.toString(searchParams.getAvoidCountries()));
        }

        if (profileParams != null && profileParams.hasWeightings()) {
            props.putObject(ProfileTools.KEY_CUSTOM_WEIGHTINGS, true);
            Iterator<ProfileWeighting> iterator = profileParams.getWeightings().getIterator();
            while (iterator.hasNext()) {
                ProfileWeighting weighting = iterator.next();
                if (!weighting.getParameters().isEmpty()) {
                    String name = ProfileWeighting.encodeName(weighting.getName());
                    for (Map.Entry<String, Object> kv : weighting.getParameters().toMap().entrySet())
                        props.putObject(name + kv.getKey(), kv.getValue());
                }
            }
        }

        String profileName = ProfileTools.makeProfileName(encoderName, WeightingMethod.getName(searchParams.getWeightingMethod()), Boolean.TRUE.equals(profile.getEncoderOptions().getTurnCosts()));
        String profileNameCH = ProfileTools.makeProfileName(encoderName, WeightingMethod.getName(searchParams.getWeightingMethod()), false);
        RouteSearchContext searchCntx = new RouteSearchContext(mGraphHopper, flagEncoder, profileName, profileNameCH);
        searchCntx.setProperties(props);

        return searchCntx;
    }

    public GHResponse computeRoundTripRoute(double lat0, double lon0, WayPointBearing
            bearing, RouteSearchParameters searchParams, Boolean geometrySimplify) throws Exception {
        GHResponse resp;

        try {
            int profileType = searchParams.getProfileType();
            int weightingMethod = searchParams.getWeightingMethod();
            RouteSearchContext searchCntx = createSearchContext(searchParams);

            List<GHPoint> points = new ArrayList<>();
            points.add(new GHPoint(lat0, lon0));
            List<Double> bearings = new ArrayList<>();
            GHRequest req;

            if (bearing != null) {
                bearings.add(bearing.getValue());
                req = new GHRequest(points, bearings);
            } else {
                req = new GHRequest(points);
            }

            req.setProfile(searchCntx.profileName());
            req.getHints().putObject(Parameters.Algorithms.RoundTrip.DISTANCE, searchParams.getRoundTripLength());
            req.getHints().putObject(Parameters.Algorithms.RoundTrip.POINTS, searchParams.getRoundTripPoints());

            if (searchParams.getRoundTripSeed() > -1) {
                req.getHints().putObject(Parameters.Algorithms.RoundTrip.SEED, searchParams.getRoundTripSeed());
            }

            PMap props = searchCntx.getProperties();
            req.setAdditionalHints(props);

            if (props != null && !props.isEmpty())
                req.getHints().putAll(props);

            if (TemporaryUtilShelter.supportWeightingMethod(profileType))
                ProfileTools.setWeightingMethod(req.getHints(), weightingMethod, profileType, false);
            else
                throw new IllegalArgumentException("Unsupported weighting " + weightingMethod + " for profile + " + profileType);

            //Roundtrip not possible with preprocessed edges.
            setSpeedups(req, false, false, true, searchCntx.profileNameCH());

            if (astarEpsilon != null)
                req.getHints().putObject("astarbi.epsilon", astarEpsilon);
            if (astarApproximation != null)
                req.getHints().putObject("astarbi.approximation", astarApproximation);
            //Overwrite algorithm selected in setSpeedups
            req.setAlgorithm(Parameters.Algorithms.ROUND_TRIP);

            mGraphHopper.getRouterConfig().setSimplifyResponse(geometrySimplify);
            resp = mGraphHopper.route(req);

        } catch (Exception ex) {
            LOGGER.error(ex);
            throw new InternalServerException(RoutingErrorCodes.UNKNOWN, "Unable to compute a route");
        }

        return resp;
    }

    /**
     * Set the speedup techniques used for calculating the route.
     * Reults in usage of CH, Core or ALT/AStar, if they are enabled.
     *
     * @param req     Request whose hints will be set
     * @param useCH   Should CH be enabled
     * @param useCore Should Core be enabled
     * @param useALT  Should ALT be enabled
     */
    public void setSpeedups(GHRequest req, boolean useCH, boolean useCore, boolean useALT, String profileNameCH) {
        String profileName = req.getProfile();

        //Priority: CH->Core->ALT
        String profileNameNoTC = profileName.replace("_with_turn_costs", "");

        useCH = useCH && mGraphHopper.isCHAvailable(profileNameCH);
        useCore = useCore && !useCH && (mGraphHopper.isCoreAvailable(profileName) || mGraphHopper.isCoreAvailable(profileNameNoTC));
        useALT = useALT && !useCH && !useCore && mGraphHopper.isLMAvailable(profileName);

        req.getHints().putObject(ProfileTools.KEY_CH_DISABLE, !useCH);
        req.getHints().putObject(ProfileTools.KEY_CORE_DISABLE, !useCore);
        req.getHints().putObject(ProfileTools.KEY_LM_DISABLE, !useALT);

        if (useCH) {
            req.setAlgorithm(Parameters.Algorithms.DIJKSTRA_BI);
            req.setProfile(profileNameCH);
        }
        if (useCore) {
            // fallback to a core profile without turn costs if one is available
            if (!mGraphHopper.isCoreAvailable(profileName) && mGraphHopper.isCoreAvailable(profileNameNoTC))
                req.setProfile(profileNameNoTC);
        }
    }

    boolean requiresTimeDependentWeighting(RouteSearchParameters searchParams, RouteSearchContext searchCntx) {
        if (!searchParams.isTimeDependent())
            return false;

        FlagEncoder flagEncoder = searchCntx.getEncoder();

        return flagEncoder.hasEncodedValue(EncodingManager.getKey(flagEncoder, ConditionalEdges.ACCESS))
                || flagEncoder.hasEncodedValue(EncodingManager.getKey(flagEncoder, ConditionalEdges.SPEED))
                || mGraphHopper.isTrafficEnabled();
    }

    /**
     * This function creates the actual {@link IsochroneMap}.
     * So the first step in the function is a checkup on that.
     *
     * @param parameters The input are {@link IsochroneSearchParameters}
     * @return The return will be an {@link IsochroneMap}
     * @throws Exception
     */
    public IsochroneMap buildIsochrone(IsochroneSearchParameters parameters) throws Exception {
        IsochroneMap result;

        try {
            RouteSearchContext searchCntx = createSearchContext(parameters.getRouteParameters());
            IsochroneMapBuilderFactory isochroneMapBuilderFactory = new IsochroneMapBuilderFactory(searchCntx);
            result = isochroneMapBuilderFactory.buildMap(parameters);
        } catch (Exception ex) {
            if (DebugUtility.isDebug()) {
                LOGGER.error(ex);
            }
            throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to build an isochrone map.");
        }

        if (result.getIsochronesCount() > 0) {
            if (parameters.hasAttribute(ProfileTools.KEY_TOTAL_POP)) {
                try {
                    Map<StatisticsProviderConfiguration, List<String>> mapProviderToAttrs = new HashMap<>();
                    StatisticsProviderConfiguration provConfig = parameters.getStatsProviders().get(ProfileTools.KEY_TOTAL_POP);
                    if (provConfig != null) {
                        List<String> attrList = new ArrayList<>();
                        attrList.add(ProfileTools.KEY_TOTAL_POP);
                        mapProviderToAttrs.put(provConfig, attrList);
                    }
                    for (Map.Entry<StatisticsProviderConfiguration, List<String>> entry : mapProviderToAttrs.entrySet()) {
                        provConfig = entry.getKey();
                        StatisticsProvider provider = StatisticsProviderFactory.getProvider(provConfig.getName(), provConfig.getParameters());
                        String[] provAttrs = provConfig.getMappedProperties(entry.getValue());

                        for (Isochrone isochrone : result.getIsochrones()) {

                            double[] attrValues = provider.getStatistics(isochrone, provAttrs);
                            isochrone.setAttributes(entry.getValue(), attrValues, provConfig.getAttribution());

                        }
                    }
                } catch (Exception ex) {
                    LOGGER.error(ex);

                    throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to compute isochrone total_pop attribute.");
                }
            }
            if (parameters.hasAttribute("reachfactor") || parameters.hasAttribute("area")) {
                for (Isochrone isochrone : result.getIsochrones()) {
                    String units = parameters.getUnits();
                    String areaUnits = parameters.getAreaUnits();
                    if (areaUnits != null) units = areaUnits;
                    double area = isochrone.calcArea(units);
                    if (parameters.hasAttribute("area")) {
                        isochrone.setArea(area);
                    }
                    if (parameters.hasAttribute("reachfactor")) {
                        double reachfactor = isochrone.calcReachfactor(units);
                        // reach factor could be > 1, which would confuse people
                        reachfactor = (reachfactor > 1) ? 1 : reachfactor;
                        isochrone.setReachfactor(reachfactor);
                    }
                }
            }
        }
        return result;
    }

    public boolean equals(Object o) {
        return o != null && o.getClass().equals(RoutingProfile.class) && this.hashCode() == o.hashCode();
    }

    public int hashCode() {
        return mGraphHopper.getGraphHopperStorage().getDirectory().getLocation().hashCode();
    }
}
