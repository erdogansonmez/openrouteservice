package org.heigit.ors.config.defaults;

import lombok.Getter;
import org.heigit.ors.common.EncoderNameEnum;
import org.heigit.ors.config.profile.storages.*;

import java.util.HashMap;
import java.util.Map;

@Getter
public class DefaultExtendedStoragesProperties {

    Map<String, ExtendedStorage> extStorages;

    public DefaultExtendedStoragesProperties() {
        extStorages = new HashMap<>();
    }

    public DefaultExtendedStoragesProperties(EncoderNameEnum encoderName) {
        this();
        if (encoderName == null) {
            encoderName = EncoderNameEnum.UNKNOWN;
        }

        switch (encoderName) {
            case DRIVING_CAR -> {
                extStorages.put("WayCategory", new ExtendedStorageWayCategory());
                extStorages.put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
                extStorages.put("HeavyVehicle", new ExtendedStorageHeavyVehicle());
                extStorages.put("RoadAccessRestrictions", new ExtendedStorageRoadAccessRestrictions(true));
                extStorages.put("Tollways", new ExtendedStorageTollways());
            }
            case DRIVING_HGV -> {
                extStorages.put("WayCategory", new ExtendedStorageWayCategory());
                extStorages.put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
                extStorages.put("HeavyVehicle", new ExtendedStorageHeavyVehicle(true));
                extStorages.put("Tollways", new ExtendedStorageTollways());
            }
            case CYCLING_REGULAR, CYCLING_MOUNTAIN, CYCLING_ROAD, CYCLING_ELECTRIC, FOOT_WALKING, FOOT_HIKING -> {
                extStorages.put("WayCategory", new ExtendedStorageWayCategory());
                extStorages.put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
                extStorages.put("HillIndex", new ExtendedStorageHillIndex());
                extStorages.put("TrailDifficulty", new ExtendedStorageTrailDifficulty());
            }
            case WHEELCHAIR -> {
                extStorages.put("WayCategory", new ExtendedStorageWayCategory());
                extStorages.put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
                extStorages.put("Wheelchair", new ExtendedStorageWheelchair(true));
                extStorages.put("OsmId", new ExtendedStorageOsmId());
            }
            case PUBLIC_TRANSPORT -> {
            }
            default -> {
                extStorages.put("WayCategory", new ExtendedStorageWayCategory());
                extStorages.put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
            }
        }
    }

}
