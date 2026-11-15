/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package de.vw.mib.asl.internal.carplay.common;

import com.mst2.carplay.navi.Mst2NaviBridge;
import de.vw.mib.asl.api.navigation.AbstractASLNavigationServicesListener;
import de.vw.mib.asl.internal.carplay.common.CarPlayGlobalProperies;
import de.vw.mib.asl.internal.carplay.common.CarPlayHMIRequestParameterConfiguration;
import de.vw.mib.asl.internal.carplay.target.HsmTarget;
import org.dsi.ifc.carplay.AppStateRequest;
import org.dsi.ifc.carplay.ResourceRequest;

public class NavigationHandler
extends AbstractASLNavigationServicesListener {
    private HsmTarget target;
    private CarPlayGlobalProperies properties;

    public NavigationHandler(HsmTarget hsmTarget, CarPlayGlobalProperies carPlayGlobalProperies) {
        this.target = hsmTarget;
        this.properties = carPlayGlobalProperies;
        try {
            Mst2NaviBridge.attachOnce(hsmTarget, carPlayGlobalProperies);
        } catch (Throwable throwable) {
        }
    }

    public void updateGuidanceActive(boolean bl) {
        if (bl && this.properties.isConnected()) {
            CarPlayHMIRequestParameterConfiguration carPlayHMIRequestParameterConfiguration = this.properties.getParamconfig();
            ResourceRequest[] resourceRequestArray = carPlayHMIRequestParameterConfiguration.getRessourceRequest(0);
            CarPlayHMIRequestParameterConfiguration carPlayHMIRequestParameterConfiguration2 = this.properties.getParamconfig();
            AppStateRequest[] appStateRequestArray = carPlayHMIRequestParameterConfiguration2.getAppStateRequest(2);
            this.target.getDSICarPlay().requestModeChange(resourceRequestArray, appStateRequestArray, "User initiated");
        } else if (!bl && this.properties.isConnected() && this.properties.getActiveNavigation() == 3) {
            CarPlayHMIRequestParameterConfiguration carPlayHMIRequestParameterConfiguration = this.properties.getParamconfig();
            ResourceRequest[] resourceRequestArray = carPlayHMIRequestParameterConfiguration.getRessourceRequest(0);
            CarPlayHMIRequestParameterConfiguration carPlayHMIRequestParameterConfiguration3 = this.properties.getParamconfig();
            AppStateRequest[] appStateRequestArray = carPlayHMIRequestParameterConfiguration3.getAppStateRequest(6);
            this.target.getDSICarPlay().requestModeChange(resourceRequestArray, appStateRequestArray, "User initiated");
        }
        this.properties.setHmiRouteGuidanceActive(bl);
    }

    public void updateServiceAvailable(boolean bl) {
    }
}
