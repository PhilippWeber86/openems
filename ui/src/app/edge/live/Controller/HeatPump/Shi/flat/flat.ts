import { CommonModule } from "@angular/common";
import { Component } from "@angular/core";
import { IonicModule } from "@ionic/angular";
import { TranslateModule } from "@ngx-translate/core";
import { ComponentsModule } from "src/app/shared/components/components.module";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { ChannelAddress, CurrentData, EdgeConfig } from "src/app/shared/shared";

@Component({
    selector: "oe-controller-heatpump-shi",
    templateUrl: "./flat.html",
    standalone: true,
    imports: [
        CommonModule,
        IonicModule,
        TranslateModule,
        ComponentsModule,
    ],
})
export class ControllerHeatPumpShiComponent extends AbstractFlatWidget {

    public override component: EdgeConfig.Component | null = null;

    protected state: string | null = null;
    protected heatPumpPower: number | null = null;
    protected freeBatteryEnergy: number | null = null;
    protected essSupportPower: number | null = null;
    protected nightReserveEnergy: number | null = null;
    protected heatPumpId: string | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        if (this.component == null) {
            return [];
        }
        const channelAddresses: ChannelAddress[] = [
            new ChannelAddress(this.componentId, "ElevatedModeActive"),
            new ChannelAddress(this.componentId, "FreeBatteryEnergy"),
            new ChannelAddress(this.componentId, "EssSupportPower"),
            new ChannelAddress(this.componentId, "NightReserveEnergy"),
        ];
        this.heatPumpId = this.component.properties["heatPump.id"] ?? null;
        if (this.heatPumpId) {
            channelAddresses.push(new ChannelAddress(this.heatPumpId, "ActivePower"));
        }
        return channelAddresses;
    }

    protected override onCurrentData(currentData: CurrentData): void {
        this.state = currentData.allComponents[this.componentId + "/ElevatedModeActive"] == 1
            ? this.translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.BOOST_ACTIVE")
            : this.translate.instant("EDGE.INDEX.WIDGETS.SHI_HEAT_PUMP.NORMAL_MODE");
        this.freeBatteryEnergy = currentData.allComponents[this.componentId + "/FreeBatteryEnergy"] ?? null;
        this.essSupportPower = currentData.allComponents[this.componentId + "/EssSupportPower"] ?? null;
        this.nightReserveEnergy = currentData.allComponents[this.componentId + "/NightReserveEnergy"] ?? null;
        if (this.heatPumpId) {
            this.heatPumpPower = currentData.allComponents[this.heatPumpId + "/ActivePower"] ?? null;
        }
    }
}
