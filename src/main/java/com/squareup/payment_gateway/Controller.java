package com.squareup.payment_gateway;

// Import statements and rest of your Java code...

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.squareup.square.*;
import com.squareup.square.api.*;
import com.squareup.square.models.*;
import com.squareup.square.models.Error;
import com.squareup.square.exceptions.*;

@RestController
@CrossOrigin(origins = "https://127.0.0.1:4200")
public class Controller {

        private final String squareEnvironment;

        public Controller(@Value("${squareEnvironment}") String squareEnvironment) {
                this.squareEnvironment = squareEnvironment;
                // Additional initialization logic if needed
        }

        // Example method to demonstrate accessing squareEnvironment
        public String getSquareEnvironment() {
                return squareEnvironment;
        }

        // Example method handling a request mapping
        // Replace with your actual endpoint and logic
        public String handleRequest() {
                // Example logic
                return "Hello, Square Payment Gateway!";
        }

        @Value("${SQUARE_ACCESS_TOKEN}")
        private String squareAccessToken;

        @Value("${SQUARE_ENVIRONMENT}")

        private SquareClient getSquareClient() {
                Environment environment = squareEnvironment.equals("production") ? Environment.PRODUCTION
                                : Environment.SANDBOX;
                return new SquareClient.Builder()
                                .accessToken(squareAccessToken)
                                .environment(environment)
                                .build();
        }

        @GetMapping("/api/get-location-id")
        public ResponseEntity<?> getLocationId() {
                SquareClient client = getSquareClient();
                LocationsApi locationsApi = client.getLocationsApi();

                try {
                        ListLocationsResponse response = locationsApi.listLocations();
                        if (response.getErrors() != null) {
                                return ResponseEntity.status(500).body(response.getErrors());
                        }

                        if (response.getLocations().isEmpty()) {
                                return ResponseEntity.status(404).body("No locations found.");
                        }

                        String locationId = response.getLocations().get(0).getId();
                        return ResponseEntity.ok().body(new LocationIdResponse(locationId));
                } catch (ApiException | IOException e) {
                        return ResponseEntity.status(500).body(e.getMessage());
                }
        }

        @PostMapping("/api/process-payment")
        public ResponseEntity<?> processPayment(@RequestBody PaymentRequest paymentRequest) {
                SquareClient client = getSquareClient();
                PaymentsApi paymentsApi = client.getPaymentsApi();

                // Set the amount and currency in the backend
                Money amountMoney = new Money.Builder()
                                .amount(1l) // Amount in cents (100 cents = $1.00)
                                .currency("USD")
                                .build();

                CreatePaymentRequest body = new CreatePaymentRequest.Builder(
                                paymentRequest.getNonce(),
                                String.valueOf(System.currentTimeMillis()))
                                .amountMoney(amountMoney)
                                .orderId(paymentRequest.getOrderId())
                                .build();

                CompletableFuture<CreatePaymentResponse> future = paymentsApi.createPaymentAsync(body);

                future.thenAccept(result -> {
                        System.out.println("Payment processed: " + result.getPayment().getId());
                }).exceptionally(exception -> {
                        System.out.println("Payment processing failed:");
                        if (exception.getCause() instanceof ApiException) {
                                ApiException apiException = (ApiException) exception.getCause();
                                System.out.println("HTTP Status Code: " + apiException.getResponseCode());
                                System.out.println("Square Error Body: " + apiException.getMessage());

                                if (apiException.getErrors() != null) {
                                        for (Error error : apiException.getErrors()) {
                                                System.out.println("Error Detail: " + error.getDetail());
                                                System.out.println("Error Category: " + error.getCategory());
                                                System.out.println("Error Code: " + error.getCode());
                                        }
                                }
                        } else {
                                exception.printStackTrace();
                        }
                        return null;
                }).join();

                return ResponseEntity.ok("Payment request submitted.");
        }

        @PostMapping("/api/create-order")
        public ResponseEntity<?> createOrder(@RequestBody OrderRequest orderRequest) {
                SquareClient client = getSquareClient();
                OrdersApi ordersApi = client.getOrdersApi();

                // Example line items (replace with your own logic to fetch or generate line
                // items)
                List<OrderLineItem> lineItems = orderRequest.getLineItems().stream()
                                .map(item -> {
                                        Money basePriceMoney = new Money.Builder()
                                                        .amount(item.getAmount())
                                                        .currency(item.getCurrency())
                                                        .build();

                                        return new OrderLineItem.Builder(item.getName())
                                                        .basePriceMoney(basePriceMoney)
                                                        .quantity(String.valueOf(item.getQuantity()))
                                                        .build();
                                })
                                .collect(Collectors.toList());

                // Generate idempotency key
                String idempotencyKey = generateIdempotencyKey();

                // Define your order details
                CreateOrderRequest createOrderRequest = new CreateOrderRequest.Builder()
                                .order(new Order.Builder(orderRequest.getLocationId()) // Use locationId from
                                                                                       // orderRequest
                                                .lineItems(lineItems)
                                                .build())
                                .idempotencyKey(idempotencyKey)
                                .build();

                try {
                        CompletableFuture<CreateOrderResponse> future = ordersApi.createOrderAsync(createOrderRequest);

                        // Handle response asynchronously
                        future.thenAccept(response -> {
                                System.out.println("Order created: " + response.getOrder().getId());
                                // You can save response.getOrder().getId() in your database or return it
                                // directly
                        }).exceptionally(exception -> {
                                exception.printStackTrace();
                                return null;
                        }).join();

                        return ResponseEntity.ok("Order creation initiated.");
                } catch (Exception e) {
                        e.printStackTrace();
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Order creation failed.");
                }
        }

        private String generateIdempotencyKey() {
                return UUID.randomUUID().toString();
        }

        public static class LocationIdResponse {
                private String locationId;

                public LocationIdResponse(String locationId) {
                        this.locationId = locationId;
                }

                public String getLocationId() {
                        return locationId;
                }

                public void setLocationId(String locationId) {
                        this.locationId = locationId;
                }
        }

        public static class OrderRequest {
                private String locationId;
                private long amount;
                private String currency;
                private List<OrderLineItemRequest> lineItems;

                // Getters and setters

                public String getLocationId() {
                        return locationId;
                }

                public void setLocationId(String locationId) {
                        this.locationId = locationId;
                }

                public long getAmount() {
                        return amount;
                }

                public void setAmount(long amount) {
                        this.amount = amount;
                }

                public String getCurrency() {
                        return currency;
                }

                public void setCurrency(String currency) {
                        this.currency = currency;
                }

                public List<OrderLineItemRequest> getLineItems() {
                        return lineItems;
                }

                public void setLineItems(List<OrderLineItemRequest> lineItems) {
                        this.lineItems = lineItems;
                }
        }

        public static class OrderLineItemRequest {
                private String name;
                private long amount;
                private String currency;
                private int quantity;

                // Getters and setters

                public String getName() {
                        return name;
                }

                public void setName(String name) {
                        this.name = name;
                }

                public long getAmount() {
                        return amount;
                }

                public void setAmount(long amount) {
                        this.amount = amount;
                }

                public String getCurrency() {
                        return currency;
                }

                public void setCurrency(String currency) {
                        this.currency = currency;
                }

                public int getQuantity() {
                        return quantity;
                }

                public void setQuantity(int quantity) {
                        this.quantity = quantity;
                }
        }
}
