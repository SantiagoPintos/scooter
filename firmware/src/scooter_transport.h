/* SPDX-License-Identifier: Apache-2.0 */
#ifndef SCOOTER_TRANSPORT_H_
#define SCOOTER_TRANSPORT_H_

#include <zephyr/bluetooth/addr.h>
#include <stdint.h>

struct scooter_gatt_handles {
	uint16_t service_start;
	uint16_t service_end;
	uint16_t capability;
	uint16_t authentication;
	uint16_t session_status;
	uint16_t application_write;
	uint16_t application_response;
};

/* Initialize BLE once. No scan or connection is started by this call. */
int scooter_transport_init(void);

/* Invoke from an application thread, not a Bluetooth callback. The link remains open on
 * success, and returned handles are valid only until close. No authentication or scooter
 * command is sent.
 */
int scooter_transport_open(const bt_addr_le_t *target, struct scooter_gatt_handles *handles);
int scooter_transport_close(void);

#endif
