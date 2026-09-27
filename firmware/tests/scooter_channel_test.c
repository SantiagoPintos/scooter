/* Synthetic compatibility checks against the Android channel framing behavior. */
/* SPDX-License-Identifier: Apache-2.0 */
#include "scooter_channel.h"

#include <assert.h>
#include <string.h>

static void test_bootstrap(void)
{
	const uint8_t stage_zero[] = {0, 0, 0xa4, 0, 0x55};
	const uint8_t stage_one[] = {0, 0, 0xa4, 1};
	uint8_t out[sizeof(stage_zero)];
	bool complete = true;
	assert(scooter_bootstrap_ack(stage_zero, sizeof(stage_zero), out, sizeof(out), &complete));
	assert(!complete && out[2] == 0xa5 && out[4] == 0x55);
	assert(scooter_bootstrap_ack(stage_one, sizeof(stage_one), out, sizeof(out), &complete));
	assert(complete && out[2] == 0xa5);
	assert(!scooter_bootstrap_ack(stage_one, 3, out, sizeof(out), &complete));
	assert(!scooter_bootstrap_ack(stage_one, sizeof(stage_one), out, 3, &complete));
}

static void test_flow_and_data(void)
{
	uint8_t out[12];
	size_t len = 0;
	struct scooter_frame_view frame;
	const uint8_t expected_flow[] = {0, 0, 0, 3, 1, 0};
	const uint8_t data[] = {9, 8, 7};
	const uint8_t expected_data[] = {1, 0, 9, 8, 7};
	assert(scooter_frame_flow_control(3, 1, out, sizeof(out), &len));
	assert(len == sizeof(expected_flow) && memcmp(out, expected_flow, len) == 0);
	assert(scooter_frame_decode(out, len, &frame));
	assert(frame.kind == SCOOTER_FRAME_FLOW_CONTROL && frame.type_or_status == 3 &&
	       frame.frame_count == 1);
	assert(scooter_frame_data(1, data, sizeof(data), out, sizeof(out), &len));
	assert(len == sizeof(expected_data) && memcmp(out, expected_data, len) == 0);
	assert(scooter_frame_decode(out, len, &frame));
	assert(frame.kind == SCOOTER_FRAME_DATA && frame.sequence == 1 &&
	       frame.payload_len == sizeof(data) && memcmp(frame.payload, data, sizeof(data)) == 0);
	assert(!scooter_frame_flow_control(3, 0, out, sizeof(out), &len));
	assert(!scooter_frame_data(0, data, sizeof(data), out, sizeof(out), &len));
	assert(!scooter_frame_data(1, data, sizeof(data), out, 4, &len));
}

static void test_ack_and_control(void)
{
	uint8_t out[16];
	size_t len = 0;
	struct scooter_frame_view frame;
	uint16_t sequence = 0;
	const uint16_t sequences[] = {1, 0x1234};
	const uint8_t expected_ack[] = {0, 0, 1, 0xff, 1, 0, 0x34, 0x12};
	const uint8_t payload[] = {6, 5};
	assert(scooter_frame_ack(-1, sequences, 2, out, sizeof(out), &len));
	assert(len == sizeof(expected_ack) && memcmp(out, expected_ack, len) == 0);
	assert(scooter_frame_decode(out, len, &frame));
	assert(frame.kind == SCOOTER_FRAME_ACK && frame.type_or_status == -1);
	assert(scooter_frame_ack_sequence(&frame, 1, &sequence) && sequence == 0x1234);
	assert(!scooter_frame_ack_sequence(&frame, 2, &sequence));
	assert(scooter_frame_single_control(3, payload, sizeof(payload), out, sizeof(out), &len));
	assert(scooter_frame_decode(out, len, &frame));
	assert(frame.kind == SCOOTER_FRAME_SINGLE_CONTROL && frame.type_or_status == 3 &&
	       frame.payload_len == sizeof(payload) &&
	       memcmp(frame.payload, payload, sizeof(payload)) == 0);
	assert(scooter_frame_single_control_ack(0, out, sizeof(out), &len));
	assert(len == 4 && out[2] == 3 && !scooter_frame_decode(out, len, &frame));
	assert(!scooter_frame_ack(0, NULL, 0, out, 3, &len));
}

static void test_invalid_frames(void)
{
	struct scooter_frame_view frame;
	const uint8_t short_control[] = {0, 0, 1};
	const uint8_t short_flow[] = {0, 0, 0, 3, 1};
	const uint8_t uneven_ack[] = {0, 0, 1, 0, 1};
	const uint8_t unknown_control[] = {0, 0, 9, 0};
	assert(!scooter_frame_decode(short_control, sizeof(short_control), &frame));
	assert(!scooter_frame_decode(short_flow, sizeof(short_flow), &frame));
	assert(!scooter_frame_decode(uneven_ack, sizeof(uneven_ack), &frame));
	assert(!scooter_frame_decode(unknown_control, sizeof(unknown_control), &frame));
}

int main(void)
{
	test_bootstrap();
	test_flow_and_data();
	test_ack_and_control();
	test_invalid_frames();
	return 0;
}
