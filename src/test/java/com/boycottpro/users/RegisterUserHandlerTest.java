package com.boycottpro.users;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class RegisterUserHandlerTest {

    @Mock
    DynamoDbClient dynamoDb;

    @Mock
    Context context;

    @Mock
    LambdaLogger logger;   // ← add this

    @InjectMocks RegisterUserHandler handler;

    @BeforeEach
    void setUp() {
        when(context.getLogger()).thenReturn(logger); // ← stub logger
    }
    private Map<String, Object> buildEvent(String sub, String email, String preferredUsername) {
        Map<String, String> attrs = new HashMap<>();
        if (sub != null)    attrs.put("sub", sub);
        if (email != null)  attrs.put("email", email);
        if (preferredUsername != null) attrs.put("preferred_username", preferredUsername);

        Map<String, Object> request = new HashMap<>();
        request.put("userAttributes", attrs);

        Map<String, Object> root = new HashMap<>();
        root.put("request", request);
        return root;
    }

    @Test
    void handleRequest_success_putsItem_andReturnsEvent() {
        Map<String, Object> event = buildEvent(
                "11111111-2222-3333-4444-555555555555",
                "user@example.com",
                "displayHandle"
        );

        Object result = handler.handleRequest(event, context);

        // Original event is returned
        assertSame(event, result);

        // PutItem called once with expected request
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(1)).putItem(captor.capture());

        PutItemRequest put = captor.getValue();
        // Basic sanity checks on key attributes (item map is opaque; we check presence)
        var item = put.item();
        org.junit.jupiter.api.Assertions.assertEquals("11111111-2222-3333-4444-555555555555",
                item.get("user_id").s());
        org.junit.jupiter.api.Assertions.assertEquals("user@example.com",
                item.get("email_addr").s());
        org.junit.jupiter.api.Assertions.assertEquals("displayHandle",
                item.get("username").s());
        org.junit.jupiter.api.Assertions.assertFalse(item.get("paying_user").bool());
        // created_ts is numeric; assert present
        org.junit.jupiter.api.Assertions.assertTrue(item.containsKey("created_ts"));
    }

    @Test
    void handleRequest_idempotent_whenAlreadyExists_doesNotThrow_andReturnsEvent() {
        // Arrange: make putItem throw ConditionalCheckFailedException
        doThrow(ConditionalCheckFailedException.builder().message("exists").build())
                .when(dynamoDb).putItem(any(PutItemRequest.class));

        Map<String, Object> event = buildEvent(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "dup@example.com",
                null
        );

        Object result = handler.handleRequest(event, context);

        assertSame(event, result);
        verify(dynamoDb, times(1)).putItem(any(PutItemRequest.class));
        // no exception propagated
    }

    @Test
    void handleRequest_missingEmailOrSub_skipsPut_andReturnsEvent() {
        Map<String, Object> eventMissingEmail = buildEvent(
                "sub-only-id",
                null,
                null
        );
        Map<String, Object> eventMissingSub = buildEvent(
                null,
                "user@example.com",
                null
        );

        Object res1 = handler.handleRequest(eventMissingEmail, context);
        Object res2 = handler.handleRequest(eventMissingSub, context);

        assertSame(eventMissingEmail, res1);
        assertSame(eventMissingSub, res2);

        // No writes attempted
        verify(dynamoDb, times(0)).putItem(any(PutItemRequest.class));
    }

    @Test
    void handleRequest_genericException_isCaught_andReturnsEvent() {
        // Force an unexpected runtime error by making putItem throw RuntimeException
        doThrow(new RuntimeException("boom"))
                .when(dynamoDb).putItem(any(PutItemRequest.class));

        Map<String, Object> event = buildEvent(
                "123e4567-e89b-12d3-a456-426614174000",
                "x@example.com",
                null
        );

        Object result = handler.handleRequest(event, context);

        assertSame(event, result);
        verify(dynamoDb, times(1)).putItem(any(PutItemRequest.class));
    }
}
