package org.heigit.ors.config.profile.defaults;

import org.heigit.ors.common.EncoderNameEnum;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.config.profile.storages.ExtendedStorageHillIndex;
import org.heigit.ors.config.profile.storages.ExtendedStorageTrailDifficulty;
import org.heigit.ors.config.profile.storages.ExtendedStorageWayCategory;
import org.heigit.ors.config.profile.storages.ExtendedStorageWaySurfaceType;

public class HikingProfileProperties extends ProfileProperties {
    public HikingProfileProperties() {
        super();
        this.setEncoderName(EncoderNameEnum.FOOT_HIKING);
        getEncoderOptions().setBlockFords(false);
        getExtStorages().put("WayCategory", new ExtendedStorageWayCategory());
        getExtStorages().put("WaySurfaceType", new ExtendedStorageWaySurfaceType());
        getExtStorages().put("HillIndex", new ExtendedStorageHillIndex());
        getExtStorages().put("TrailDifficulty", new ExtendedStorageTrailDifficulty());
    }
}
