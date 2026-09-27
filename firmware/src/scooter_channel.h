/* SPDX-License-Identifier: Apache-2.0 */
#ifndef SCOOTER_CHANNEL_H_
#define SCOOTER_CHANNEL_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum scooter_frame_kind {
	SCOOTER_FRAME_FLOW_CONTROL,
	SCOOTER_FRAME_ACK,
	SCOOTER_FRAME_SINGLE_CONTROL,
	SCOOTER_FRAME_MANAGEMENT,
	SCOOTER_FRAME_DATA,
};

/* The payload points into the input frame and is valid only while that frame is retained. */
struct scooter_frame_view {
	enum scooter_frame_kind kind;
	uint16_t sequence;
	uint16_t frame_count;
	int16_t type_or_status;
	const uint8_t *payload;
	size_t payload_len;
};

bool scooter_bootstrap_ack(const uint8_t *incoming, size_t len,
			   uint8_t *out, size_t out_capacity, bool *complete);

bool scooter_frame_decode(const uint8_t *frame, size_t len, struct scooter_frame_view *out);
bool scooter_frame_ack_sequence(const struct scooter_frame_view *ack, size_t index,
				uint16_t *sequence);

bool scooter_frame_flow_control(uint8_t packet_type, uint16_t frame_count,
				uint8_t *out, size_t out_capacity, size_t *out_len);
bool scooter_frame_ack(int16_t status, const uint16_t *sequences, size_t sequence_count,
		       uint8_t *out, size_t out_capacity, size_t *out_len);
bool scooter_frame_single_control(uint8_t packet_type, const uint8_t *payload,
				  size_t payload_len, uint8_t *out, size_t out_capacity,
				  size_t *out_len);
bool scooter_frame_single_control_ack(uint8_t status, uint8_t *out,
				      size_t out_capacity, size_t *out_len);
bool scooter_frame_data(uint16_t sequence, const uint8_t *payload, size_t payload_len,
			uint8_t *out, size_t out_capacity, size_t *out_len);

#endif
