import { TranslateService } from "@ngx-translate/core";
import { NavigationConstants, NavigationTree } from "src/app/shared/components/navigation/shared";
import { Name } from "src/app/shared/components/shared/name";
import { EdgeConfig } from "src/app/shared/shared";

export namespace SharedControllerHeatPumpShi {

    /**
     * Builds the navigation tree of the SHI heat-pump Controller for the new
     * navigation. Without it the widget would only be reachable from the old
     * combined live view, because the new navigation renders widgets as
     * navigation nodes instead of a single widget list.
     *
     * @param translate the {@link TranslateService}
     * @param component the Controller {@link EdgeConfig.Component}
     * @return the {@link NavigationTree} constructor parameters
     */
    export function getNavigationTree(
        translate: TranslateService,
        component: EdgeConfig.Component,
    ): ConstructorParameters<typeof NavigationTree> {
        return new NavigationTree(
            component.id,
            { baseString: "controller/heatpump-shi/" + component.id },
            { name: "oe-heatpump", color: "normal" },
            Name.METER_ALIAS_OR_ID(component),
            "label",
            [
                NavigationConstants.CommonNodes.INFO(translate, {
                    source: component.id,
                }),
            ],
            null,
        ).toConstructorParams();
    }
}
