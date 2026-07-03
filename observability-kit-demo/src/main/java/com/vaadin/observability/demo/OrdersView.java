/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.demo;

import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

/**
 * Orders list. Every row has a "Ship" button; shipping order 1042 throws, all
 * others succeed. The demo scenario: a user reports "I clicked Ship and got an
 * error" with no further detail.
 */
@Route("orders")
public class OrdersView extends VerticalLayout {

    static final class Order {
        private final long id;
        private final String customer;
        private final String item;
        private String status = "Pending";

        Order(long id, String customer, String item) {
            this.id = id;
            this.customer = customer;
            this.item = item;
        }

        long getId() {
            return id;
        }

        String getCustomer() {
            return customer;
        }

        String getItem() {
            return item;
        }

        String getStatus() {
            return status;
        }
    }

    private final OrderShippingService shippingService = new OrderShippingService();

    public OrdersView() {
        add(new H1("Orders"));

        List<Order> orders = List.of(new Order(1040, "Acme Corp", "4x Widget"),
                new Order(1041, "Globex", "1x Gadget"),
                new Order(1042, "Initech", "2x Widget"),
                new Order(1043, "Umbrella", "6x Gizmo"));

        Grid<Order> grid = new Grid<>();
        grid.setItems(orders);
        grid.addColumn(Order::getId).setHeader("Order").setFlexGrow(0);
        grid.addColumn(Order::getCustomer).setHeader("Customer");
        grid.addColumn(Order::getItem).setHeader("Items");
        grid.addColumn(Order::getStatus).setHeader("Status");
        grid.addComponentColumn(order -> {
            Button ship = new Button("Ship", event -> {
                shippingService.ship(order.getId());
                order.status = "Shipped";
                grid.getDataProvider().refreshItem(order);
                Notification success = Notification
                        .show("Order #%d shipped".formatted(order.getId()));
                success.setPosition(Notification.Position.MIDDLE);
                success.addThemeVariants(NotificationVariant.SUCCESS);
            });
            ship.setId("ship-" + order.getId());
            return ship;
        }).setHeader("Actions").setFlexGrow(0);
        grid.setAllRowsVisible(true);

        add(grid);
    }
}
