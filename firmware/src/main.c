/*
 * Bring-up image: Bluetooth initializes, but no target scan or scooter command starts on boot.
 * SPDX-License-Identifier: Apache-2.0
 */

#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>

#include "scooter_transport.h"

#define STATUS_LED_NODE DT_ALIAS(led0)

#if !DT_NODE_EXISTS(STATUS_LED_NODE)
#error "The selected board must expose the led0 devicetree alias"
#endif

static const struct gpio_dt_spec status_led = GPIO_DT_SPEC_GET(STATUS_LED_NODE, gpios);

int main(void)
{
	if (!gpio_is_ready_dt(&status_led)) {
		return 1;
	}

	if (gpio_pin_configure_dt(&status_led, GPIO_OUTPUT_INACTIVE) != 0) {
		return 1;
	}
	if (scooter_transport_init() != 0) {
		return 1;
	}

	for (;;) {
		gpio_pin_toggle_dt(&status_led);
		k_msleep(500);
	}
}
