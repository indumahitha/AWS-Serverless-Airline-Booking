package com.indu.airline;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.time.Instant;
import java.util.*;

public class FlightSearchHandler implements RequestHandler<Map<String, Object>, Object> {

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {

        Map<String, Object> requestContext =
                (Map<String, Object>) input.get("requestContext");

        if (requestContext == null)
            return errorResponse(400, "Invalid request");

        Map<String, Object> http =
                (Map<String, Object>) requestContext.get("http");

        if (http == null)
            return errorResponse(400, "Invalid HTTP context");

        String method = (String) http.get("method");
        String rawPath = (String) input.get("rawPath");

        if (method == null || rawPath == null)
            return errorResponse(400, "Invalid request structure");

        // ================= ROUTING =================

        if ("GET".equalsIgnoreCase(method) && rawPath.endsWith("/search")) {
            return handleSearch(input);
        }

        if ("POST".equalsIgnoreCase(method) && rawPath.endsWith("/booking")) {
            return handleBooking(input);
        }

        if ("GET".equalsIgnoreCase(method) && rawPath.endsWith("/bookings")) {
            return handleViewBookings(input);
        }

        return errorResponse(404, "Route not found");
    }

    // ================= SEARCH =================

    private Object handleSearch(Map<String, Object> input) {

        try {

            Map<String, Object> queryParams =
                    (Map<String, Object>) input.get("queryStringParameters");

            String sourceInput = null;
            String destinationInput = null;

            if (queryParams != null) {
                sourceInput = (String) queryParams.get("source");
                destinationInput = (String) queryParams.get("destination");
            }

            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
            DynamoDB dynamoDB = new DynamoDB(client);
            Table table = dynamoDB.getTable("Flights");

            ItemCollection<ScanOutcome> items = table.scan();
            List<Map<String, Object>> flights = new ArrayList<>();

            for (Item item : items) {

                String source = item.getString("source");
                String destination = item.getString("destination");

                if (sourceInput != null && destinationInput != null &&
                        source.equalsIgnoreCase(sourceInput) &&
                        destination.equalsIgnoreCase(destinationInput)) {

                    Map<String, Object> flight = new HashMap<>();
                    flight.put("flightId", item.getString("flightId"));
                    flight.put("source", source);
                    flight.put("destination", destination);
                    flight.put("date", item.getString("date"));
                    flight.put("availableSeats", item.getNumber("availableSeats"));
                    flight.put("price", item.getNumber("price"));

                    flights.add(flight);
                }
            }

            return successResponse(flights);

        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    // ================= BOOKING =================

    private Object handleBooking(Map<String, Object> input) {

        try {

            String body = (String) input.get("body");

            if (body == null || body.isEmpty())
                return errorResponse(400, "Request body required");

            body = body.replace("{", "")
                    .replace("}", "")
                    .replace("\"", "");

            String[] parts = body.split(",");

            String flightId = null;
            String userName = null;

            for (String part : parts) {
                String[] keyValue = part.split(":");
                if (keyValue[0].trim().equals("flightId"))
                    flightId = keyValue[1].trim();
                if (keyValue[0].trim().equals("userName"))
                    userName = keyValue[1].trim();
            }

            if (flightId == null || userName == null)
                return errorResponse(400, "flightId and userName required");

            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
            DynamoDB dynamoDB = new DynamoDB(client);

            Table flightsTable = dynamoDB.getTable("Flights");

            Item flightItem = flightsTable.getItem("flightId", flightId);

            if (flightItem == null)
                return errorResponse(404, "Flight not found");

            int availableSeats = flightItem.getInt("availableSeats");

            if (availableSeats <= 0)
                return errorResponse(400, "No seats available");

            UpdateItemSpec updateSpec = new UpdateItemSpec()
                    .withPrimaryKey("flightId", flightId)
                    .withUpdateExpression("set availableSeats = :val")
                    .withValueMap(new ValueMap().withNumber(":val", availableSeats - 1));

            flightsTable.updateItem(updateSpec);

            String bookingId = UUID.randomUUID().toString();

            Table bookingsTable = dynamoDB.getTable("Bookings");

            bookingsTable.putItem(
                    new Item()
                            .withPrimaryKey("bookingId", bookingId)
                            .withString("flightId", flightId)
                            .withString("userName", userName)
                            .withString("bookingTime", Instant.now().toString())
                            .withString("status", "CONFIRMED")
            );

            Map<String, Object> result = new HashMap<>();
            result.put("bookingId", bookingId);
            result.put("status", "CONFIRMED");

            return successResponse(result);

        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    // ================= VIEW BOOKINGS =================

    private Object handleViewBookings(Map<String, Object> input) {

        try {

            Map<String, Object> queryParams =
                    (Map<String, Object>) input.get("queryStringParameters");

            String userName = null;

            if (queryParams != null)
                userName = (String) queryParams.get("userName");

            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
            DynamoDB dynamoDB = new DynamoDB(client);
            Table bookingsTable = dynamoDB.getTable("Bookings");

            ItemCollection<ScanOutcome> items = bookingsTable.scan();
            List<Map<String, Object>> bookings = new ArrayList<>();

            for (Item item : items) {

                if (userName == null ||
                        userName.equalsIgnoreCase(item.getString("userName"))) {

                    Map<String, Object> booking = new HashMap<>();
                    booking.put("bookingId", item.getString("bookingId"));
                    booking.put("flightId", item.getString("flightId"));
                    booking.put("userName", item.getString("userName"));
                    booking.put("bookingTime", item.getString("bookingTime"));
                    booking.put("status", item.getString("status"));

                    bookings.add(booking);
                }
            }

            return successResponse(bookings);

        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    // ================= COMMON RESPONSES =================

    private Object successResponse(Object body) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        response.put("body", body.toString());
        return response;
    }

    private Object errorResponse(int statusCode, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        response.put("body", message);
        return response;
    }
}