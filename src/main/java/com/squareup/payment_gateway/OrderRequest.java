package com.squareup.payment_gateway;

import java.util.List;

public class OrderRequest {
    private List<OrderItem> items;

    // Getters and setters

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public static class OrderItem {
        private String name;
        private String quantity;
        private long price;

        // Getters and setters

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getQuantity() {
            return quantity;
        }

        public void setQuantity(String quantity) {
            this.quantity = quantity;
        }

        public long getPrice() {
            return price;
        }

        public void setPrice(long price) {
            this.price = price;
        }
    }
}
