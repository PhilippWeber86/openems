package io.openems.edge.heat.shi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;

class HeatShiHeatPumpImplTest {

	@Test
	void testDefineModbusProtocolReadOnly() throws Exception {
		var sut = new HeatShiHeatPumpImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("heatPump0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setReadOnly(true) //
						.build());

		var tasks = sut.defineModbusProtocol().getTaskManager().getTasks();

		assertEquals(9, tasks.size());
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC3ReadRegistersTask && t.getStartAddress() == 10000));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC3ReadRegistersTask && t.getStartAddress() == 10005));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC3ReadRegistersTask && t.getStartAddress() == 10040));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC3ReadRegistersTask && t.getStartAddress() == 10070));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC4ReadInputRegistersTask && t.getStartAddress() == 10000));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC4ReadInputRegistersTask && t.getStartAddress() == 10002));
		assertTrue(tasks.stream().anyMatch(t -> t instanceof FC4ReadInputRegistersTask && t.getStartAddress() == 10301));
		assertFalse(tasks.stream().anyMatch(FC6WriteRegisterTask.class::isInstance));
	}

	@Test
	void testConfigurableMeterType() throws Exception {
		var sut = new HeatShiHeatPumpImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("heatPump0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setReadOnly(true) //
						.setType(MeterType.CONSUMPTION_NOT_METERED) //
						.build());

		assertEquals(MeterType.CONSUMPTION_NOT_METERED, sut.getMeterType());
		assertEquals(true, sut.getReadOnlyModeChannel().getNextValue().orElse(false));
	}

	@Test
	void testDefineModbusProtocolNotReadOnly() throws Exception {
		var sut = new HeatShiHeatPumpImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("heatPump0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setReadOnly(false) //
						.build());

		var tasks = sut.defineModbusProtocol().getTaskManager().getTasks();

		assertEquals(19, tasks.size());
		for (var address : new int[] { 10000, 10001, 10002, 10005, 10006, 10007, 10040, 10041, 10070, 10071 }) {
			assertTrue(
					tasks.stream()
							.anyMatch(t -> t instanceof FC6WriteRegisterTask && t.getStartAddress() == address),
					"Missing write task for register " + address);
		}
	}
}
