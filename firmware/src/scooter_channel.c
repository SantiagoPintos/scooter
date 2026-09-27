/* SPDX-License-Identifier: Apache-2.0 */
#include "scooter_channel.h"

#include <string.h>

enum {
	FLOW_CONTROL = 0,
	ACK = 1,
	SINGLE_CONTROL = 2,
	SINGLE_CONTROL_ACK = 3,
	MANAGEMENT = 4,
};

static uint16_t read_le16(const uint8_t *bytes)
{
	return (uint16_t)bytes[0] | ((uint16_t)bytes[1] << 8);
}

static void write_le16(uint8_t *bytes, uint16_t value)
{
	bytes[0] = (uint8_t)value;
	bytes[1] = (uint8_t)(value >> 8);
}

bool scooter_bootstrap_ack(const uint8_t *incoming, size_t len,
			   uint8_t *out, size_t out_capacity, bool *complete)
{
	if (incoming == NULL || out == NULL || complete == NULL || len < 4 ||
	    out_capacity < len || incoming[0] != 0 || incoming[1] != 0 ||
	    incoming[2] != 0xa4) {
		return false;
	}

	memmove(out, incoming, len);
	out[2] = 0xa5;
	*complete = incoming[3] == 1;
	return true;
}

bool scooter_frame_decode(const uint8_t *frame, size_t len, struct scooter_frame_view *out)
{
	if (frame == NULL || out == NULL || len < 2) {
		return false;
	}

	uint16_t sequence = read_le16(frame);
	if (sequence != 0) {
		*out = (struct scooter_frame_view) {
			.kind = SCOOTER_FRAME_DATA,
			.sequence = sequence,
			.payload = frame + 2,
			.payload_len = len - 2,
		};
		return true;
	}
	if (len < 4) {
		return false;
	}

	struct scooter_frame_view decoded = {
		.type_or_status = frame[3],
		.payload = frame + 4,
		.payload_len = len - 4,
	};
	switch (frame[2]) {
	case FLOW_CONTROL:
		if (len != 6) {
			return false;
		}
		decoded.kind = SCOOTER_FRAME_FLOW_CONTROL;
		decoded.frame_count = read_le16(frame + 4);
		decoded.payload = NULL;
		decoded.payload_len = 0;
		break;
	case ACK:
		if ((len - 4) % 2 != 0) {
			return false;
		}
		decoded.kind = SCOOTER_FRAME_ACK;
		decoded.type_or_status = (int8_t)frame[3];
		break;
	case SINGLE_CONTROL:
		decoded.kind = SCOOTER_FRAME_SINGLE_CONTROL;
		break;
	case MANAGEMENT:
		decoded.kind = SCOOTER_FRAME_MANAGEMENT;
		break;
	case SINGLE_CONTROL_ACK:
	default:
		/* The Android peer decoder also rejects kind 3 and unknown controls. */
		return false;
	}

	*out = decoded;
	return true;
}

bool scooter_frame_ack_sequence(const struct scooter_frame_view *ack, size_t index,
				uint16_t *sequence)
{
	if (ack == NULL || sequence == NULL || ack->kind != SCOOTER_FRAME_ACK ||
	    index >= ack->payload_len / 2 || ack->payload == NULL) {
		return false;
	}
	*sequence = read_le16(ack->payload + index * 2);
	return true;
}

bool scooter_frame_flow_control(uint8_t packet_type, uint16_t frame_count,
				uint8_t *out, size_t out_capacity, size_t *out_len)
{
	if (out == NULL || out_len == NULL || out_capacity < 6 || frame_count == 0) {
		return false;
	}
	write_le16(out, 0);
	out[2] = FLOW_CONTROL;
	out[3] = packet_type;
	write_le16(out + 4, frame_count);
	*out_len = 6;
	return true;
}

bool scooter_frame_ack(int16_t status, const uint16_t *sequences, size_t sequence_count,
		       uint8_t *out, size_t out_capacity, size_t *out_len)
{
	if (out == NULL || out_len == NULL || out_capacity < 4 ||
	    status < -128 || status > 255 ||
	    (sequence_count != 0 && sequences == NULL) ||
	    sequence_count > (out_capacity >= 4 ? (out_capacity - 4) / 2 : 0)) {
		return false;
	}
	write_le16(out, 0);
	out[2] = ACK;
	out[3] = (uint8_t)status;
	for (size_t i = 0; i < sequence_count; ++i) {
		write_le16(out + 4 + i * 2, sequences[i]);
	}
	*out_len = 4 + sequence_count * 2;
	return true;
}

bool scooter_frame_single_control(uint8_t packet_type, const uint8_t *payload,
				  size_t payload_len, uint8_t *out, size_t out_capacity,
				  size_t *out_len)
{
	if (out == NULL || out_len == NULL || out_capacity < 4 ||
	    payload_len > out_capacity - 4 || (payload_len != 0 && payload == NULL)) {
		return false;
	}
	write_le16(out, 0);
	out[2] = SINGLE_CONTROL;
	out[3] = packet_type;
	if (payload_len != 0) {
		memmove(out + 4, payload, payload_len);
	}
	*out_len = 4 + payload_len;
	return true;
}

bool scooter_frame_single_control_ack(uint8_t status, uint8_t *out,
				      size_t out_capacity, size_t *out_len)
{
	if (out == NULL || out_len == NULL || out_capacity < 4) {
		return false;
	}
	write_le16(out, 0);
	out[2] = SINGLE_CONTROL_ACK;
	out[3] = status;
	*out_len = 4;
	return true;
}

bool scooter_frame_data(uint16_t sequence, const uint8_t *payload, size_t payload_len,
			uint8_t *out, size_t out_capacity, size_t *out_len)
{
	if (out == NULL || out_len == NULL || out_capacity < 2 || sequence == 0 ||
	    payload_len > out_capacity - 2 || (payload_len != 0 && payload == NULL)) {
		return false;
	}
	write_le16(out, sequence);
	if (payload_len != 0) {
		memmove(out + 2, payload, payload_len);
	}
	*out_len = 2 + payload_len;
	return true;
}
