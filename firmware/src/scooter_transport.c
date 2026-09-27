/* SPDX-License-Identifier: Apache-2.0 */
#include "scooter_transport.h"

#include <errno.h>
#include <stdbool.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>

#define REQUIRED_ATT_MTU 247
#define OP_TIMEOUT K_SECONDS(10)

static struct bt_uuid_16 scooter_service = BT_UUID_INIT_16(0xfe95);
static struct bt_uuid_16 capability_uuid = BT_UUID_INIT_16(0x0004);
static struct bt_uuid_16 authentication_uuid = BT_UUID_INIT_16(0x0016);
static struct bt_uuid_16 session_status_uuid = BT_UUID_INIT_16(0x0010);
static struct bt_uuid_16 application_write_uuid = BT_UUID_INIT_16(0x001a);
static struct bt_uuid_16 application_response_uuid = BT_UUID_INIT_16(0x001b);

K_MUTEX_DEFINE(operation_lock);
K_SEM_DEFINE(scan_done, 0, 1);
K_SEM_DEFINE(connection_done, 0, 1);
K_SEM_DEFINE(gatt_done, 0, 1);
K_SEM_DEFINE(disconnect_done, 0, 1);

static bool initialized;
static bt_addr_le_t target_address;
static atomic_t scanning;
static atomic_t target_found;
static atomic_t link_disconnected;
static atomic_t connection_error;
static atomic_t mtu_error;
static atomic_t subscription_error;
static atomic_t capability_read;
static uint8_t authentication_properties;
static struct bt_conn *link;
static struct scooter_gatt_handles found_handles;
static struct bt_gatt_exchange_params mtu_params;
static struct bt_gatt_discover_params service_discover_params;
static struct bt_gatt_discover_params characteristic_discover_params;
static struct bt_gatt_read_params read_params;
static struct bt_gatt_subscribe_params authentication_subscription;
static struct bt_gatt_discover_params authentication_ccc_discover;

static void advertisement_found(const bt_addr_le_t *addr, int8_t rssi, uint8_t type,
				struct net_buf_simple *ad)
{
	ARG_UNUSED(rssi);
	ARG_UNUSED(ad);
	if (!atomic_get(&scanning) ||
	    (type != BT_GAP_ADV_TYPE_ADV_IND && type != BT_GAP_ADV_TYPE_ADV_DIRECT_IND) ||
	    bt_addr_le_cmp(addr, &target_address) != 0) {
		return;
	}
	if (atomic_cas(&target_found, 0, 1)) {
		k_sem_give(&scan_done);
	}
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (bt_addr_le_cmp(bt_conn_get_dst(conn), &target_address) != 0) {
		return;
	}
	atomic_set(&connection_error, err);
	k_sem_give(&connection_done);
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(reason);
	if (bt_addr_le_cmp(bt_conn_get_dst(conn), &target_address) != 0) {
		return;
	}
	atomic_set(&link_disconnected, 1);
	k_sem_give(&connection_done);
	k_sem_give(&gatt_done);
	k_sem_give(&disconnect_done);
}

BT_CONN_CB_DEFINE(scooter_connection_callbacks) = {
	.connected = connected,
	.disconnected = disconnected,
};

static void mtu_exchanged(struct bt_conn *conn, uint8_t err,
			  struct bt_gatt_exchange_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	atomic_set(&mtu_error, err);
	k_sem_give(&gatt_done);
}

static uint8_t service_discovered(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				  struct bt_gatt_discover_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	if (attr != NULL && attr->user_data != NULL) {
		const struct bt_gatt_service_val *service = attr->user_data;
		found_handles.service_start = attr->handle;
		found_handles.service_end = service->end_handle;
	}
	k_sem_give(&gatt_done);
	return BT_GATT_ITER_STOP;
}

static uint8_t characteristic_discovered(struct bt_conn *conn, const struct bt_gatt_attr *attr,
					 struct bt_gatt_discover_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	if (attr == NULL) {
		k_sem_give(&gatt_done);
		return BT_GATT_ITER_STOP;
	}
	if (attr->user_data == NULL) {
		return BT_GATT_ITER_CONTINUE;
	}

	const struct bt_gatt_chrc *chrc = attr->user_data;
	uint16_t *handle = NULL;
	if (bt_uuid_cmp(chrc->uuid, &capability_uuid.uuid) == 0) {
		handle = &found_handles.capability;
	} else if (bt_uuid_cmp(chrc->uuid, &authentication_uuid.uuid) == 0) {
		handle = &found_handles.authentication;
	} else if (bt_uuid_cmp(chrc->uuid, &session_status_uuid.uuid) == 0) {
		handle = &found_handles.session_status;
	} else if (bt_uuid_cmp(chrc->uuid, &application_write_uuid.uuid) == 0) {
		handle = &found_handles.application_write;
	} else if (bt_uuid_cmp(chrc->uuid, &application_response_uuid.uuid) == 0) {
		handle = &found_handles.application_response;
	}
	if (handle != NULL) {
		*handle = chrc->value_handle;
		if (handle == &found_handles.authentication) {
			authentication_properties = chrc->properties;
		}
	}
	return BT_GATT_ITER_CONTINUE;
}

static uint8_t authentication_notification(struct bt_conn *conn,
					   struct bt_gatt_subscribe_params *params,
					   const void *data, uint16_t length)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	ARG_UNUSED(data);
	ARG_UNUSED(length);
	/* The bootstrap consumer is the next protocol layer; do not inspect or log bytes here. */
	return BT_GATT_ITER_CONTINUE;
}

static void authentication_subscribed(struct bt_conn *conn, uint8_t err,
				      struct bt_gatt_subscribe_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	atomic_set(&subscription_error, err);
	k_sem_give(&gatt_done);
}

static uint8_t capability_received(struct bt_conn *conn, uint8_t err,
				   struct bt_gatt_read_params *params,
				   const void *data, uint16_t length)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	ARG_UNUSED(length);
	if (err == 0 && data != NULL) {
		/* The value is intentionally not logged or retained. */
		atomic_set(&capability_read, 1);
	}
	k_sem_give(&gatt_done);
	return BT_GATT_ITER_STOP;
}

int scooter_transport_init(void)
{
	if (initialized) {
		return 0;
	}
	int err = bt_enable(NULL);
	if (err == 0) {
		initialized = true;
	}
	return err;
}

static int wait_for_operation(struct k_sem *completed)
{
	int err = k_sem_take(completed, OP_TIMEOUT);
	if (err != 0) {
		return err;
	}
	return atomic_get(&link_disconnected) ? -ENOTCONN : 0;
}

static int close_locked(void)
{
	int err = 0;
	if (atomic_set(&scanning, 0)) {
		err = bt_le_scan_stop();
	}
	if (link != NULL) {
		if (!atomic_get(&link_disconnected)) {
			k_sem_reset(&disconnect_done);
			int disconnect_err = bt_conn_disconnect(link,
						  BT_HCI_ERR_REMOTE_USER_TERM_CONN);
			if (disconnect_err == 0) {
				int wait_err = k_sem_take(&disconnect_done, OP_TIMEOUT);
				if (wait_err != 0 && err == 0) {
					err = wait_err;
				}
			} else if (disconnect_err != -ENOTCONN && err == 0) {
				err = disconnect_err;
			}
		}
		bt_conn_unref(link);
		link = NULL;
	}
	memset(&found_handles, 0, sizeof(found_handles));
	return err;
}

int scooter_transport_close(void)
{
	k_mutex_lock(&operation_lock, K_FOREVER);
	int err = close_locked();
	k_mutex_unlock(&operation_lock);
	return err;
}

int scooter_transport_open(const bt_addr_le_t *target, struct scooter_gatt_handles *handles)
{
	if (!initialized || target == NULL || handles == NULL) {
		return -EINVAL;
	}
	k_mutex_lock(&operation_lock, K_FOREVER);
	if (link != NULL || atomic_get(&scanning)) {
		k_mutex_unlock(&operation_lock);
		return -EBUSY;
	}

	target_address = *target;
	memset(&found_handles, 0, sizeof(found_handles));
	atomic_set(&target_found, 0);
	atomic_set(&link_disconnected, 0);
	atomic_set(&connection_error, 0);
	atomic_set(&mtu_error, 0);
	atomic_set(&subscription_error, 0);
	atomic_set(&capability_read, 0);
	authentication_properties = 0;
	k_sem_reset(&scan_done);
	atomic_set(&scanning, 1);
	int err = bt_le_scan_start(BT_LE_SCAN_PASSIVE, advertisement_found);
	if (err != 0) {
		atomic_set(&scanning, 0);
		goto done;
	}
	err = wait_for_operation(&scan_done);
	if (err == 0 && !atomic_get(&target_found)) {
		err = -ENOENT;
	}
	atomic_set(&scanning, 0);
	int stop_err = bt_le_scan_stop();
	if (err == 0 && stop_err != 0) {
		err = stop_err;
	}
	if (err != 0) {
		goto done;
	}

	k_sem_reset(&connection_done);
	err = bt_conn_le_create(&target_address, BT_CONN_LE_CREATE_CONN,
				BT_LE_CONN_PARAM_DEFAULT, &link);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&connection_done);
	if (err != 0) {
		goto done;
	}
	if (atomic_get(&connection_error) != 0) {
		err = -ECONNREFUSED;
		goto done;
	}

	mtu_params.func = mtu_exchanged;
	k_sem_reset(&gatt_done);
	err = bt_gatt_exchange_mtu(link, &mtu_params);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&gatt_done);
	if (err != 0) {
		goto done;
	}
	if (atomic_get(&mtu_error) != 0 || bt_gatt_get_mtu(link) < REQUIRED_ATT_MTU) {
		err = -EMSGSIZE;
		goto done;
	}

	memset(&service_discover_params, 0, sizeof(service_discover_params));
	service_discover_params.uuid = &scooter_service.uuid;
	service_discover_params.func = service_discovered;
	service_discover_params.start_handle = BT_ATT_FIRST_ATTRIBUTE_HANDLE;
	service_discover_params.end_handle = BT_ATT_LAST_ATTRIBUTE_HANDLE;
	service_discover_params.type = BT_GATT_DISCOVER_PRIMARY;
	k_sem_reset(&gatt_done);
	err = bt_gatt_discover(link, &service_discover_params);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&gatt_done);
	if (err != 0) {
		goto done;
	}
	if (found_handles.service_start == 0 ||
	    found_handles.service_end <= found_handles.service_start) {
		err = -ENOENT;
		goto done;
	}

	memset(&characteristic_discover_params, 0, sizeof(characteristic_discover_params));
	characteristic_discover_params.func = characteristic_discovered;
	characteristic_discover_params.start_handle = found_handles.service_start + 1;
	characteristic_discover_params.end_handle = found_handles.service_end;
	characteristic_discover_params.type = BT_GATT_DISCOVER_CHARACTERISTIC;
	k_sem_reset(&gatt_done);
	err = bt_gatt_discover(link, &characteristic_discover_params);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&gatt_done);
	if (err != 0) {
		goto done;
	}
	if (found_handles.capability == 0 || found_handles.authentication == 0 ||
	    found_handles.session_status == 0 || found_handles.application_write == 0 ||
	    found_handles.application_response == 0) {
		err = -ENOENT;
		goto done;
	}
	uint16_t subscription_value =
		(authentication_properties & BT_GATT_CHRC_INDICATE) ? BT_GATT_CCC_INDICATE :
		(authentication_properties & BT_GATT_CHRC_NOTIFY) ? BT_GATT_CCC_NOTIFY : 0;
	if (subscription_value == 0) {
		err = -ENOTSUP;
		goto done;
	}
	memset(&authentication_subscription, 0, sizeof(authentication_subscription));
	memset(&authentication_ccc_discover, 0, sizeof(authentication_ccc_discover));
	authentication_subscription.notify = authentication_notification;
	authentication_subscription.subscribe = authentication_subscribed;
	authentication_subscription.value_handle = found_handles.authentication;
	authentication_subscription.ccc_handle = BT_GATT_AUTO_DISCOVER_CCC_HANDLE;
	authentication_subscription.end_handle = found_handles.service_end;
	authentication_subscription.disc_params = &authentication_ccc_discover;
	authentication_subscription.value = subscription_value;
	atomic_set_bit(authentication_subscription.flags, BT_GATT_SUBSCRIBE_FLAG_VOLATILE);
	k_sem_reset(&gatt_done);
	err = bt_gatt_subscribe(link, &authentication_subscription);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&gatt_done);
	if (err != 0) {
		goto done;
	}
	if (atomic_get(&subscription_error) != 0) {
		err = -EIO;
		goto done;
	}

	memset(&read_params, 0, sizeof(read_params));
	read_params.func = capability_received;
	read_params.handle_count = 1;
	read_params.single.handle = found_handles.capability;
	k_sem_reset(&gatt_done);
	err = bt_gatt_read(link, &read_params);
	if (err != 0) {
		goto done;
	}
	err = wait_for_operation(&gatt_done);
	if (err != 0) {
		goto done;
	}
	if (!atomic_get(&capability_read)) {
		err = -EIO;
		goto done;
	}

	*handles = found_handles;

done:
	if (err != 0) {
		(void)close_locked();
	}
	k_mutex_unlock(&operation_lock);
	return err;
}
