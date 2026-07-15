/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v1
 *
 * Helper-class shadow (NOT an ASL TID-target -- safe to shadow; vehicle-proven in testing-11).
 * Stock CarPlay instantiates this listener with the live HsmTarget + CarPlay properties; the only
 * addition over stock is the attachOnce hook in the ctor. All stock method bodies are preserved
 * verbatim so stock CarPlay guidance mode-changes keep working.
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
        try {
            Mst2NaviBridge.markShadowCtorEntered(hsmTarget);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        this.target = hsmTarget;
        this.properties = carPlayGlobalProperies;
        try {
            Mst2NaviBridge.attachOnce(hsmTarget, carPlayGlobalProperies);
        }
        catch (Throwable throwable) {
            // empty catch block
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
