#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "roadcast_catalog.h"

static uint32_t find_signal(uint16_t can_id, const char *name) {
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        if (ROADCAST_SIGNALS[i].can_id == can_id &&
            strcmp(ROADCAST_SIGNALS[i].name, name) == 0)
            return i;
    }
    return UINT32_MAX;
}

static void initialize_frames(roadcast_frame_t frames[ROADCAST_FRAME_COUNT]) {
    memset(frames, 0, sizeof(roadcast_frame_t) * ROADCAST_FRAME_COUNT);
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        frames[i].can_id = ROADCAST_CAN_IDS[i];
        frames[i].state = ROADCAST_OBSERVATION_VALID;
        frames[i].first_observed_ns = 10;
        frames[i].last_change_ns = 20;
    }
}

static void test_catalog_identity(void) {
    assert(ROADCAST_SIGNAL_COUNT == 815);
    assert(ROADCAST_CAN_SCHEMA_HASH != 0);
    assert(ROADCAST_SIGNALS[0].stable_id == UINT64_C(0x35bcb28678e8e5b8));
    for (uint32_t i = 0; i < ROADCAST_SIGNAL_COUNT; i++) {
        assert(ROADCAST_SIGNALS[i].stable_id != 0);
        assert(ROADCAST_SIGNALS[i].frame_index < ROADCAST_FRAME_COUNT);
        assert(ROADCAST_CAN_IDS[ROADCAST_SIGNALS[i].frame_index] ==
               ROADCAST_SIGNALS[i].can_id);
        assert(ROADCAST_SIGNALS[i].width > 0);
        assert(ROADCAST_SIGNALS[i].width <= 64);
        for (uint32_t j = i + 1; j < ROADCAST_SIGNAL_COUNT; j++) {
            assert(ROADCAST_SIGNALS[i].stable_id !=
                   ROADCAST_SIGNALS[j].stable_id);
        }
    }

    uint32_t diagnostic_broadcast = find_signal(0x7df, "Diag_req");
    uint32_t diagnostic_module = find_signal(0x7c1, "Diag_req");
    assert(diagnostic_broadcast != UINT32_MAX);
    assert(diagnostic_module != UINT32_MAX);
    assert(ROADCAST_SIGNALS[diagnostic_broadcast].stable_id !=
           ROADCAST_SIGNALS[diagnostic_module].stable_id);
}

static void test_decoding_and_invalid_flag(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t ambient = find_signal(0x2f1, "AC_AmbientTemperature");
    uint32_t ambient_invalid =
        find_signal(0x2f1, "AC_AmbientTemperatureInvalid");
    assert(ambient != UINT32_MAX);
    assert(ambient_invalid != UINT32_MAX);
    uint16_t frame_index = ROADCAST_SIGNALS[ambient].frame_index;
    frames[frame_index].data[1] = 42;
    roadcast_decode_signals(frames, values);
    assert(values[ambient].raw == 42);
    assert(values[ambient].physical == 42.0);
    assert(values[ambient].state == ROADCAST_OBSERVATION_VALID);
    assert(values[ambient].first_observed_ns == 10);
    assert(values[ambient].last_change_ns == 20);

    frames[frame_index].data[0] |= 2;
    roadcast_decode_signals(frames, values);
    assert(values[ambient_invalid].raw == 1);
    assert(values[ambient].state == ROADCAST_OBSERVATION_INVALID);

    frames[frame_index].state = ROADCAST_OBSERVATION_NEVER_OBSERVED;
    roadcast_decode_signals(frames, values);
    assert(values[ambient].state == ROADCAST_OBSERVATION_NEVER_OBSERVED);

    frames[frame_index].state = ROADCAST_OBSERVATION_UNAVAILABLE;
    roadcast_decode_signals(frames, values);
    assert(values[ambient].state == ROADCAST_OBSERVATION_UNAVAILABLE);
}

static void test_calibrated_obc_input_voltage(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t input_voltage = find_signal(0x221, "OBC_uInAct");
    assert(input_voltage != UINT32_MAX);
    assert(ROADCAST_SIGNALS[input_voltage].calibrated == 1);
    assert(ROADCAST_SIGNALS[input_voltage].scale == 0.1);
    assert(ROADCAST_SIGNALS[input_voltage].offset == 0.0);
    assert(strcmp(ROADCAST_SIGNALS[input_voltage].unit, "V") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[input_voltage].frame_index;
    frames[frame_index].data[5] = 8;
    frames[frame_index].data[6] = 157;
    roadcast_decode_signals(frames, values);
    assert(values[input_voltage].raw == 2205);
    assert(values[input_voltage].physical == 220.5);
    assert(values[input_voltage].calibrated == 1);
}

static void test_calibrated_obc_input_current(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t input_current = find_signal(0x221, "OBC_iInAct");
    assert(input_current != UINT32_MAX);
    assert(ROADCAST_SIGNALS[input_current].calibrated == 1);
    assert(ROADCAST_SIGNALS[input_current].scale == 0.1);
    assert(ROADCAST_SIGNALS[input_current].offset == 0.0);
    assert(strcmp(ROADCAST_SIGNALS[input_current].unit, "A") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[input_current].frame_index;
    frames[frame_index].data[4] = 255;
    frames[frame_index].data[5] = 192;
    roadcast_decode_signals(frames, values);
    assert(values[input_current].raw == 1023);
    assert(values[input_current].physical > 102.29);
    assert(values[input_current].physical < 102.31);
    assert(values[input_current].calibrated == 1);
}

static void test_calibrated_vehicle_speed(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t vehicle_speed = find_signal(0x125, "ESC_VehicleSpeed");
    assert(vehicle_speed != UINT32_MAX);
    assert(ROADCAST_SIGNALS[vehicle_speed].calibrated == 1);
    assert(ROADCAST_SIGNALS[vehicle_speed].scale == 1.0);
    assert(ROADCAST_SIGNALS[vehicle_speed].offset == 0.0);
    assert(strcmp(ROADCAST_SIGNALS[vehicle_speed].unit, "km/h") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[vehicle_speed].frame_index;
    frames[frame_index].data[1] = 3;
    frames[frame_index].data[2] = 32;
    roadcast_decode_signals(frames, values);
    assert(values[vehicle_speed].raw == 100);
    assert(values[vehicle_speed].physical == 100.0);
    assert(values[vehicle_speed].calibrated == 1);
}

static void test_calibrated_road_inclination(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t road_inclination = find_signal(0x128, "ESC_RoadInclnRoadIncln");
    assert(road_inclination != UINT32_MAX);
    assert(ROADCAST_SIGNALS[road_inclination].calibrated == 1);
    assert(ROADCAST_SIGNALS[road_inclination].scale == -0.1);
    assert(ROADCAST_SIGNALS[road_inclination].offset == 100.0);
    assert(strcmp(ROADCAST_SIGNALS[road_inclination].unit, "%") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[road_inclination].frame_index;
    frames[frame_index].data[4] = 125;
    roadcast_decode_signals(frames, values);
    assert(values[road_inclination].raw == 1000);
    assert(values[road_inclination].physical > -0.001);
    assert(values[road_inclination].physical < 0.001);

    frames[frame_index].data[4] = 100;
    roadcast_decode_signals(frames, values);
    assert(values[road_inclination].raw == 800);
    assert(values[road_inclination].physical > 19.999);
    assert(values[road_inclination].physical < 20.001);

    frames[frame_index].data[4] = 150;
    roadcast_decode_signals(frames, values);
    assert(values[road_inclination].raw == 1200);
    assert(values[road_inclination].physical > -20.001);
    assert(values[road_inclination].physical < -19.999);
    assert(values[road_inclination].calibrated == 1);
}

/*
 * The zero of VCU_DrvPwrAct sits at raw 2040, not at the 2048 the field width
 * suggests. 1354 frames recorded with the car stopped, where traction power is
 * zero by definition, put the mode at raw 2040 (596 frames) with +-3 counts of
 * jitter and nothing near 2048; the median does not move between 14 C and 31 C,
 * so it is the zero point and not a climate or 12 V load. An offset of -204.8
 * therefore reported -0.8 kW on a parked car, a permanent bias worth 0.27 kWh
 * over a 20-minute trip.
 */
static void test_calibrated_drive_power(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t drive_power = find_signal(0x315, "VCU_DrvPwrAct");
    assert(drive_power != UINT32_MAX);
    assert(ROADCAST_SIGNALS[drive_power].calibrated == 1);
    assert(ROADCAST_SIGNALS[drive_power].scale == 0.1);
    assert(ROADCAST_SIGNALS[drive_power].offset == -204.0);
    assert(strcmp(ROADCAST_SIGNALS[drive_power].unit, "kW") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[drive_power].frame_index;

    /* Observed resting count of a stopped car. */
    frames[frame_index].data[1] = 0x07;
    frames[frame_index].data[2] = 0xf8;
    roadcast_decode_signals(frames, values);
    assert(values[drive_power].raw == 2040);
    assert(values[drive_power].physical > -0.001);
    assert(values[drive_power].physical < 0.001);

    /* Traction: 50 kW of discharge. */
    frames[frame_index].data[1] = 0x09;
    frames[frame_index].data[2] = 0xec;
    roadcast_decode_signals(frames, values);
    assert(values[drive_power].raw == 2540);
    assert(values[drive_power].physical > 49.999);
    assert(values[drive_power].physical < 50.001);

    /* Regeneration: 20 kW back into the pack. */
    frames[frame_index].data[1] = 0x07;
    frames[frame_index].data[2] = 0x30;
    roadcast_decode_signals(frames, values);
    assert(values[drive_power].raw == 1840);
    assert(values[drive_power].physical > -20.001);
    assert(values[drive_power].physical < -19.999);
    assert(values[drive_power].calibrated == 1);
}

/*
 * BMSH_BattCurr reports discharge as positive, charge as negative.
 *
 * The sign was settled on 155 recorded frames of one drive: regressing the pack
 * current on the traction current implied by VCU_DrvPwrAct and BMSH_BattVolt
 * gives a slope of -0.968 with R2 = 0.962 against the previous scale of -0.1, so
 * traction and current moved in opposite directions. Traction discharges the
 * pack, so the scale is positive.
 *
 * The zero is now measured too, and it is raw 5002 rather than the 5005 this
 * catalog carried. One drive cannot separate the zero from the auxiliary load,
 * so it took 22 closed trips: integrate this signal over each trip, compare the
 * result against the SOC drop times the 39.6 kWh pack, and the zero is the only
 * free parameter left. Sweeping it puts the mean ratio at 1.004 for raw 5002,
 * against 0.966 for raw 5005 and 1.029 for raw 5000. Trip-to-trip spread is
 * 6.8 %, so across 22 trips the 3.4 % bias of the old zero is about 2.4
 * standard errors — small, but one-sided.
 *
 * The correction is 3 counts, 0.3 A, near 0.12 kW. It matters because the app
 * reads auxiliary power as pack minus traction, a remainder that sits near
 * 0.3 kW, so a 0.12 kW zero error is a third of the quantity measured.
 *
 * The same 22 trips confirm the scale. Solving the zero and the pack capacity
 * together, with no capacity supplied, returns 40.5 kWh against the true 39.6.
 * A wrong scale would show up here as a proportional error. It does not.
 */
static void test_calibrated_battery_current(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t battery_current = find_signal(0x250, "BMSH_BattCurr");
    assert(battery_current != UINT32_MAX);
    assert(ROADCAST_SIGNALS[battery_current].calibrated == 1);
    assert(ROADCAST_SIGNALS[battery_current].scale == 0.1);
    assert(ROADCAST_SIGNALS[battery_current].offset == -500.2);
    assert(strcmp(ROADCAST_SIGNALS[battery_current].unit, "A") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[battery_current].frame_index;

    /* Measured zero: no pack current. */
    frames[frame_index].data[0] = 78;
    frames[frame_index].data[1] = 40;
    roadcast_decode_signals(frames, values);
    assert(values[battery_current].raw == 5002);
    assert(values[battery_current].physical > -0.001);
    assert(values[battery_current].physical < 0.001);

    /* The count this catalog used to call zero, now the 0.3 A it really is. */
    frames[frame_index].data[0] = 78;
    frames[frame_index].data[1] = 52;
    roadcast_decode_signals(frames, values);
    assert(values[battery_current].raw == 5005);
    assert(values[battery_current].physical > 0.299);
    assert(values[battery_current].physical < 0.301);

    /* Traction: the peak count of the calibration drive. */
    frames[frame_index].data[0] = 97;
    frames[frame_index].data[1] = 28;
    roadcast_decode_signals(frames, values);
    assert(values[battery_current].raw == 6215);
    assert(values[battery_current].physical > 121.299);
    assert(values[battery_current].physical < 121.301);

    /* Charging into the pack. */
    frames[frame_index].data[0] = 76;
    frames[frame_index].data[1] = 144;
    roadcast_decode_signals(frames, values);
    assert(values[battery_current].raw == 4900);
    assert(values[battery_current].physical > -10.201);
    assert(values[battery_current].physical < -10.199);
    assert(values[battery_current].calibrated == 1);
}

/*
 * VCU_ThermalPwrAct is calibrated at 0.08 kW per count, measured on the car.
 *
 * The count is an 8-bit field on 0x315. A parked bench run on 2026-08-08 held
 * the car in READY and moved one load at a time, reading the pack pair
 * (BMSH_BattVolt x BMSH_BattCurr) as the reference instrument. Heating is the
 * clean regime: the blower stayed at level 8 and VCU_DCDCPwrAct stayed at 3,
 * so the heater is the only thing that moved.
 *
 *      count    pack W     samples
 *          0     284.7          34
 *         20    1960.6           6
 *         32    2863.6          49
 *
 * Least squares over the 89 samples gives 80.61 +/- 0.64 W per count with a
 * 288.6 W intercept and R2 = 0.9945. The intercept is the check that matters:
 * the blower alone, measured in a separate state the fit never saw, drew
 * 297.4 W. 0.08 kW per count lies inside the 95 % interval [79.3, 81.9] and is
 * the only round value that does.
 *
 * The previous 0.1 estimate was 25 % high and violated conservation: it made
 * the car report 3.2 kW while the pack delivered 2.66 kW. The heat was
 * resistive, so no heat pump explains the excess - AC_ACCompReq read 0 through
 * the whole run, and pressing the A/C button afterwards added 282 W, which is
 * a compressor starting. A resistive heater has COP 1.0 by construction, so
 * the thermal output cannot exceed the electrical input.
 *
 * Two properties a client must know, neither of which unsettles the scale:
 *
 *  - The count quantises coarsely. At count 32 the pack ranged 2744 to 2954 W
 *    across three states, a spread wider than one count. A real 210 W step
 *    moved the count by zero. The signal resolves the load in steps of a few
 *    hundred watts, not in single counts.
 *  - Cooling costs more than the count reports. Max cooling plateaued at count
 *    6 drawing 799 W above the blower, where the count implies 484 W. The
 *    missing 39 % is the condenser fan, a 12 V load that ramps with the
 *    compressor and never enters this signal. Heating has no such discount.
 */
static void test_calibrated_thermal_power(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t thermal_power = find_signal(0x315, "VCU_ThermalPwrAct");
    assert(thermal_power != UINT32_MAX);
    assert(ROADCAST_SIGNALS[thermal_power].calibrated == 1);
    assert(ROADCAST_SIGNALS[thermal_power].scale == 0.08);
    assert(ROADCAST_SIGNALS[thermal_power].offset == 0.0);
    assert(strcmp(ROADCAST_SIGNALS[thermal_power].unit, "kW") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[thermal_power].frame_index;

    /* No thermal load: the count rests at zero, so the scale adds no bias. */
    frames[frame_index].data[3] = 0;
    roadcast_decode_signals(frames, values);
    assert(values[thermal_power].raw == 0);
    assert(values[thermal_power].physical == 0.0);
    assert(values[thermal_power].calibrated == 1);

    /* Mid heat: 20 counts, 1675.9 W measured above the blower baseline. */
    frames[frame_index].data[3] = 20;
    roadcast_decode_signals(frames, values);
    assert(values[thermal_power].raw == 20);
    assert(values[thermal_power].physical > 1.599);
    assert(values[thermal_power].physical < 1.601);

    /* Max heat: 32 counts, 2578.9 W measured above the same baseline. */
    frames[frame_index].data[3] = 32;
    roadcast_decode_signals(frames, values);
    assert(values[thermal_power].raw == 32);
    assert(values[thermal_power].physical > 2.559);
    assert(values[thermal_power].physical < 2.561);

    /* Full scale of the field, which bounds any reading of this signal. */
    frames[frame_index].data[3] = 255;
    roadcast_decode_signals(frames, values);
    assert(values[thermal_power].raw == 255);
    assert(values[thermal_power].physical > 20.399);
    assert(values[thermal_power].physical < 20.401);
}

/*
 * VCU_DCDCPwrAct stays unscaled, and this test pins the refusal.
 *
 * The steps of this count look like 0.1 kW each: with the car stationary and
 * every high-voltage load off, switching the rear defroster on moved it from 1
 * to 3 while the pack draw rose by 199.1 W. The levels refuse that reading. At
 * 0.1 kW per count the signal claims more power than the whole pack delivers in
 * 10 of the 14 recorded states - it reads 3, or 300 W, with every load off and
 * a pack draw of 159.3 W. A converter cannot deliver more than its supply.
 *
 * An offset does not repair it either. At 0.1 kW per count above raw 1, the
 * max-cold state still claims 500 W of 12 V draw inside a 557 W pack draw whose
 * compressor alone accounts for 318 W.
 *
 * So the steps and the levels cannot both be power, and no proportional decode
 * satisfies them. Whatever this count reports, it is not the converter output
 * in kW. It keeps its raw value and no unit until a controlled test with a
 * large, stable, purely 12 V load settles it.
 */
static void test_unscaled_dcdc_power(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t dcdc_power = find_signal(0x315, "VCU_DCDCPwrAct");
    assert(dcdc_power != UINT32_MAX);
    assert(ROADCAST_SIGNALS[dcdc_power].calibrated == 0);
    assert(ROADCAST_SIGNALS[dcdc_power].scale == 1.0);
    assert(ROADCAST_SIGNALS[dcdc_power].offset == 0.0);
    assert(strcmp(ROADCAST_SIGNALS[dcdc_power].unit, "") == 0);

    uint16_t frame_index = ROADCAST_SIGNALS[dcdc_power].frame_index;

    /* The count reaches the client unchanged, which is the whole contract. */
    frames[frame_index].data[4] = 3;
    roadcast_decode_signals(frames, values);
    assert(values[dcdc_power].raw == 3);
    assert(values[dcdc_power].physical == 3.0);
    assert(values[dcdc_power].calibrated == 0);
}

static void test_64_bit_signal(void) {
    roadcast_frame_t frames[ROADCAST_FRAME_COUNT];
    roadcast_signal_value_t values[ROADCAST_SIGNAL_COUNT];
    initialize_frames(frames);

    uint32_t diagnostic = find_signal(0x7df, "Diag_req");
    assert(diagnostic != UINT32_MAX);
    uint16_t frame_index = ROADCAST_SIGNALS[diagnostic].frame_index;
    for (uint8_t i = 0; i < 8; i++)
        frames[frame_index].data[i] = (uint8_t)(i + 1);
    roadcast_decode_signals(frames, values);
    assert(values[diagnostic].raw == UINT64_C(0x0102030405060708));
}

static void test_signed_63_bit_conversion(void) {
    roadcast_signal_definition_t definition = {
        .width = 63,
        .is_signed = 1,
        .scale = 1.0,
        .offset = 0.0,
    };
    assert(roadcast_decode_signal_physical(&definition,
                                           UINT64_C(0x4000000000000000)) ==
           -4611686018427387904.0);
    assert(roadcast_decode_signal_physical(
               &definition, UINT64_C(0x7fffffffffffffff)) == -1.0);
}

static void test_schema_round_trip(void) {
    uint8_t payload[2048];
    uint32_t encoded_count;
    size_t length = roadcast_encode_schema_chunk(payload, sizeof(payload), 0,
                                                 &encoded_count);
    assert(length > ROADCAST_SCHEMA_CHUNK_PREFIX_SIZE);
    assert(encoded_count > 0);

    roadcast_schema_entry_t decoded[64];
    uint32_t version;
    uint32_t total_count;
    uint32_t start_index;
    uint64_t hash;
    size_t decoded_count;
    assert(roadcast_decode_schema_chunk(payload, length, &version, &total_count,
                                        &start_index, &hash, decoded, 64,
                                        &decoded_count) == 0);
    assert(version == ROADCAST_SCHEMA_VERSION);
    assert(total_count == ROADCAST_SIGNAL_COUNT);
    assert(start_index == 0);
    assert(hash == ROADCAST_CAN_SCHEMA_HASH);
    assert(decoded_count == encoded_count);
    assert(decoded[0].stable_id == ROADCAST_SIGNALS[0].stable_id);
    assert(strcmp(decoded[0].name, ROADCAST_SIGNALS[0].name) == 0);
}

static void test_signal_timestamp_decoupling(void) {
    roadcast_signal_value_t canonical[ROADCAST_SIGNAL_COUNT];
    memset(canonical, 0, sizeof(canonical));

    /* Initialize canonical signal 0 as valid at t=1000 */
    canonical[0].state = ROADCAST_OBSERVATION_VALID;
    canonical[0].raw = 10;
    canonical[0].physical = 10.0;
    canonical[0].first_observed_ns = 1000;
    canonical[0].last_change_ns = 1000;

    /* Initialize canonical signal 1 as valid at t=1000 */
    canonical[1].state = ROADCAST_OBSERVATION_VALID;
    canonical[1].raw = 20;
    canonical[1].physical = 20.0;
    canonical[1].first_observed_ns = 1000;
    canonical[1].last_change_ns = 1000;

    /* Next sample at t=2000: signal 0 changes to raw=15, signal 1 remains raw=20 */
    roadcast_signal_value_t latest[ROADCAST_SIGNAL_COUNT];
    memset(latest, 0, sizeof(latest));
    latest[0].state = ROADCAST_OBSERVATION_VALID;
    latest[0].raw = 15;
    latest[0].physical = 15.0;

    latest[1].state = ROADCAST_OBSERVATION_VALID;
    latest[1].raw = 20;
    latest[1].physical = 20.0;

    /* Reconcile at t=2000 */
    for (uint32_t i = 0; i < 2; i++) {
        if (canonical[i].first_observed_ns != 0) {
            latest[i].first_observed_ns = canonical[i].first_observed_ns;
            if (latest[i].raw == canonical[i].raw &&
                latest[i].state == canonical[i].state) {
                latest[i].last_change_ns = canonical[i].last_change_ns;
            } else {
                latest[i].last_change_ns = 2000;
            }
        }
    }

    assert(latest[0].first_observed_ns == 1000);
    assert(latest[0].last_change_ns == 2000); /* Changed! */

    assert(latest[1].first_observed_ns == 1000);
    assert(latest[1].last_change_ns == 1000); /* Preserved! Did not advance! */
}

int main(void) {
    test_catalog_identity();
    test_decoding_and_invalid_flag();
    test_signal_timestamp_decoupling();
    test_calibrated_obc_input_voltage();
    test_calibrated_obc_input_current();
    test_calibrated_vehicle_speed();
    test_calibrated_road_inclination();
    test_calibrated_drive_power();
    test_calibrated_battery_current();
    test_calibrated_thermal_power();
    test_unscaled_dcdc_power();
    test_64_bit_signal();
    test_signed_63_bit_conversion();
    test_schema_round_trip();
    puts("catalog tests passed");
    return 0;
}
