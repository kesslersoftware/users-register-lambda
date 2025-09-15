package com.boycottpro.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class RegisterUserHandler implements RequestHandler<Map<String, Object>, Object> {

    private static final String TABLE_NAME = "users";
    private final DynamoDbClient dynamoDb;

    public RegisterUserHandler() { this(DynamoDbClient.create()); } // keeps prod behavior
    public RegisterUserHandler(DynamoDbClient dynamoDb) { this.dynamoDb = dynamoDb; }

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        String userId = null;
        String email = null;
        try {
            // event.request.userAttributes.{sub,email,preferred_username}
            Map<String,Object> request = (Map<String,Object>) event.get("request");
            Map<String,String> attrs   = (Map<String,String>) request.get("userAttributes");

            userId = attrs.get("sub");                 // stable UUID -> use as user_id
            email = attrs.get("email");
            String prefUser = attrs.get("preferred_username");  // may be null

            if (userId == null || email == null) {
                context.getLogger().log("Missing sub or email in PostConfirmation event");
                return event;
            }

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("user_id",     AttributeValue.fromS(userId));
            item.put("email_addr",  AttributeValue.fromS(email));
            item.put("username",    AttributeValue.fromS(prefUser == null ? email : prefUser));
            item.put("paying_user", AttributeValue.fromBool(false));
            item.put("created_ts",  AttributeValue.fromN(Long.toString(Instant.now().toEpochMilli())));

            PutItemRequest put = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .conditionExpression("attribute_not_exists(user_id)") // idempotent
                    .build();

            dynamoDb.putItem(put);
            context.getLogger().log("User " + userId + " with email: " + email + " registered successfully");
        } catch (ConditionalCheckFailedException e) {
            // already exists, safe to ignore'
            context.getLogger().log("User " + userId + " with email: " + email + " already exists; skipping");
        } catch (Exception e) {
            context.getLogger().log("User " + userId + " with email: " + email +
                    "PostConfirmation error: " + e.getMessage());
        }
        return event; // MUST return the event to Cognito
    }
}
