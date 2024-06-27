package com.squareup.payment_gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.squareup.square.Environment;
import com.squareup.square.SquareClient;
import com.squareup.square.api.LocationsApi;
import com.squareup.square.api.OrdersApi;
import com.squareup.square.api.PaymentsApi;
import com.squareup.square.exceptions.ApiException;
import com.squareup.square.models.CreateOrderRequest;
import com.squareup.square.models.CreateOrderResponse;
import com.squareup.square.models.CreatePaymentRequest;
import com.squareup.square.models.CreatePaymentResponse;
import com.squareup.square.models.Error;
import com.squareup.square.models.ListLocationsResponse;
import com.squareup.square.models.Money;
import com.squareup.square.models.Order;
import com.squareup.square.models.OrderLineItem;

@RestController
@CrossOrigin(origins = "https://127.0.0.1:4200")
public class Controller {

    private static final Logger logger = LoggerFactory.getLogger(Controller.class);

    private final String squareEnvironment;

    public Controller(@Value("${squareEnvironment}") String squareEnvironment) {
        this.squareEnvironment = squareEnvironment;
    }

    @Value("${SQUARE_ACCESS_TOKEN}")
    private String squareAccessToken;

    private SquareClient getSquareClient() {
        Environment environment = squareEnvironment.equals("production") ? Environment.PRODUCTION : Environment.SANDBOX;
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

        Money amountMoney = new Money.Builder()
                .amount(1L)
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
            logger.info("Payment processed: {}", result.getPayment().getId());
        }).exceptionally(exception -> {
            logger.error("Payment processing failed:", exception);
            if (exception.getCause() instanceof ApiException) {
                ApiException apiException = (ApiException) exception.getCause();
                logger.error("HTTP Status Code: {}", apiException.getResponseCode());
                logger.error("Square Error Body: {}", apiException.getMessage());

                if (apiException.getErrors() != null) {
                    for (Error error : apiException.getErrors()) {
                        logger.error("Error Detail: {}", error.getDetail());
                        logger.error("Error Category: {}", error.getCategory());
                        logger.error("Error Code: {}", error.getCode());
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

        String idempotencyKey = generateIdempotencyKey();

        CreateOrderRequest createOrderRequest = new CreateOrderRequest.Builder()
                .order(new Order.Builder(orderRequest.getLocationId())
                        .lineItems(lineItems)
                        .build())
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            CompletableFuture<CreateOrderResponse> future = ordersApi.createOrderAsync(createOrderRequest);

            future.thenAccept(response -> {
                logger.info("Order created: {}", response.getOrder().getId());
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

    @PostMapping("/payment-webhook")
    public ResponseEntity<String> paymentWebHook(@RequestBody WebhookPayload webhookPayload) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String payloadString = objectMapper.writeValueAsString(webhookPayload);
            logger.info("Payment webhook received: {}", payloadString);
        } catch (JsonProcessingException e) {
            logger.error("Error processing webhook payload: ", e);
        }
        // Process the webhook payload
        return ResponseEntity.ok("Webhook processed successfully");
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

    public static class WebhookPayload {
        @JsonProperty("merchant_id")
        private String merchantId;

        @JsonProperty("type")
        private String type;

        @JsonProperty("event_id")
        private String eventId;

        @JsonProperty("created_at")
        private String createdAt;

        @JsonProperty("data")
        private Data data;

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        public static class Data {
            @JsonProperty("type")
            private String type;

            @JsonProperty("id")
            private String id;

            @JsonProperty("object")
            private Object object;

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public Object getObject() {
                return object;
            }

            public void setObject(Object object) {
                this.object = object;
            }

            public static class Object {
                @JsonProperty("order_created")
                private OrderCreated orderCreated;

                public OrderCreated getOrderCreated() {
                    return orderCreated;
                }

                public void setOrderCreated(OrderCreated orderCreated) {
                    this.orderCreated = orderCreated;
                }

                public static class OrderCreated {
                    @JsonProperty("created_at")
                    private String createdAt;

                    @JsonProperty("location_id")
                    private String locationId;

                    @JsonProperty("order_id")
                    private String orderId;

                    @JsonProperty("state")
                    private String state;

                    @JsonProperty("version")
                    private int version;

                    public String getCreatedAt() {
                        return createdAt;
                    }

                    public void setCreatedAt(String createdAt) {
                        this.createdAt = createdAt;
                    }

                    public String getLocationId() {
                        return locationId;
                    }

                    public void setLocationId(String locationId) {
                        this.locationId = locationId;
                    }

                    public String getOrderId() {
                        return orderId;
                    }

                    public void setOrderId(String orderId) {
                        this.orderId = orderId;
                    }

                    public String getState() {
                        return state;
                    }

                    public void setState(String state) {
                        this.state = state;
                    }

                    public int getVersion() {
                        return version;
                    }

                    public void setVersion(int version) {
                        this.version = version;
                    }

                    @Override
                    public String toString() {
                        return "OrderCreated{" +
                                "createdAt='" + createdAt + '\'' +
                                ", locationId='" + locationId + '\'' +
                                ", orderId='" + orderId + '\'' +
                                ", state='" + state + '\'' +
                                ", version=" + version +
                                '}';
                    }
                }

                @Override
                public String toString() {
                    return "Object{" +
                            "orderCreated=" + orderCreated +
                            '}';
                }
            }

            @Override
            public String toString() {
                return "Data{" +
                        "type='" + type + '\'' +
                        ", id='" + id + '\'' +
                        ", object=" + object +
                        '}';
            }
        }

        @Override
        public String toString() {
            return "WebhookPayload{" +
                    "merchantId='" + merchantId + '\'' +
                    ", type='" + type + '\'' +
                    ", eventId='" + eventId + '\'' +
                    ", createdAt='" + createdAt + '\'' +
                    ", data=" + data +
                    '}';
        }
    }

    public static class PaymentRequest {
        private String nonce;
        private String orderId;

        public String getNonce() {
            return nonce;
        }

        public void setNonce(String nonce) {
            this.nonce = nonce;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }
    }
}
