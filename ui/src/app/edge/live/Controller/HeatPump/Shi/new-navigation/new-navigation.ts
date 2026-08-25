import { CommonModule } from "@angular/common";
import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { ReactiveFormsModule } from "@angular/forms";
import { ActivatedRoute } from "@angular/router";
import { IonicModule } from "@ionic/angular";
import { FormlyModule } from "@ngx-formly/core";
import { TranslateModule, TranslateService } from "@ngx-translate/core";
import { LiveDataService } from "src/app/edge/live/livedataservice";
import { Converter } from "src/app/shared/components/shared/converter";
import { DataService } from "src/app/shared/components/shared/dataservice";
import { AbstractFormlyComponent, OeFormlyField, OeFormlyView } from "src/app/shared/components/shared/oe-formly-component";
import { EdgeConfig } from "src/app/shared/shared";
import { AssertionUtils } from "src/app/shared/utils/assertions/assertions.utils";

@Component({
    selector: "oe-controller-heatpump-shi-home",
    templateUrl: "../../../../../../shared/components/formly/formly-field-modal/template.html",
    standalone: true,
    imports: [CommonModule, IonicModule, ReactiveFormsModule, FormlyModule, TranslateModule],
    changeDetection: ChangeDetectionStrategy.Eager,
    providers: [{ provide: DataService, useClass: LiveDataService }],
})
export class ControllerHeatPumpShiHomeComponent extends AbstractFormlyComponent {

    protected override formlyWrapper: "formly-field-modal" | "formly-field-navigation" = "formly-field-navigation";
    private route: ActivatedRoute = inject(ActivatedRoute);

    /**
     * Builds the view of the SHI heat-pump Controller for the new navigation.
     *
     * @param translate the {@link TranslateService}
     * @param component the Controller {@link EdgeConfig.Component}
     * @return the {@link OeFormlyView}
     */
    public static getFormlyGeneralView(
        translate: TranslateService,
        component: EdgeConfig.Component,
    ): OeFormlyView {
        const lines: OeFormlyField[] = [
            {
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.BOOST_ACTIVE"),
                channel: component.id + "/ElevatedModeActive",
                converter: Converter.ON_OFF(translate),
            },
            {
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.RUN_EXTENSION"),
                channel: component.id + "/RunExtensionActive",
                converter: Converter.ON_OFF(translate),
            },
        ];

        // The heat pump is a separate device; its power is only shown when the
        // Controller is actually linked to one.
        const heatPumpId = component.properties["heatPump.id"];
        if (heatPumpId) {
            lines.push({
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.HEAT_PUMP_POWER"),
                channel: heatPumpId + "/ActivePower",
                converter: Converter.POWER_IN_KILO_WATT,
            });
        }

        lines.push(
            {
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.FREE_BATTERY_ENERGY"),
                channel: component.id + "/FreeBatteryEnergy",
                converter: Converter.WATT_HOURS_IN_KILO_WATT_HOURS,
            },
            {
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.BATTERY_SUPPORT_ACTIVE"),
                channel: component.id + "/EssSupportPower",
                converter: Converter.POWER_IN_KILO_WATT,
            },
            {
                type: "channel-line",
                name: translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.NIGHT_RESERVE"),
                channel: component.id + "/NightReserveEnergy",
                converter: Converter.WATT_HOURS_IN_KILO_WATT_HOURS,
            },
        );

        return {
            title: component.alias,
            lines: lines,
            component: component,
        };
    }

    protected override generateView(): OeFormlyView {
        const edge = this.service.currentEdge();
        const config = edge.getCurrentConfig();
        AssertionUtils.assertIsDefined(config);

        const component = config.getComponentSafely(this.route.snapshot.params.componentId);
        AssertionUtils.assertIsDefined(component);

        return ControllerHeatPumpShiHomeComponent.getFormlyGeneralView(this.translate, component);
    }
}
