package com.aws.codestar.demo.handler;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.util.IOUtils;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.handlers.TracingHandler;
import com.aws.codestar.demo.model.ServerlessInput;
import com.aws.codestar.demo.model.ServerlessOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

//Handler for requests to Lambda function.
public class FeedbackHandler implements RequestHandler<ServerlessInput, Object> {
    private static final String tableName = System.getenv("TABLE_NAME");
    private static final String regionName = System.getenv("AWS_REGION");
    private static final String xRayTracing = System.getenv("XRAY_TRACING");
    private AmazonDynamoDB client = null;
    private DynamoDB dynamoDB = null;

    public Object handleRequest(ServerlessInput input, final Context context) {
        Map<String, String> headers = new HashMap<>();

        if (input.getHttpMethod().equalsIgnoreCase("post")) {
            persistData(input);

            headers.put("Content-Type", "application/json");
            return new ServerlessOutput("{ \"Output\": \"Data Saved\"}", headers, 200);
        } else {
            if (input.getPath().contains("feedback")) {
                headers.put("Content-Type", "application/json");
                return new ServerlessOutput(retrieveData(), headers, 200);
            }
            headers.put("Content-Type", "text/html");
            return new ServerlessOutput(getIndexFile(), headers, 200);
        }
    }

    private void persistData(ServerlessInput input) throws ConditionalCheckFailedException {
        initDynamo();
        dynamoDB.getTable(tableName)
                .putItem(new Item()
                        .withString("id", UUID.randomUUID().toString())
                        .withLong("ts", System.currentTimeMillis() / 1000)
                        .withJSON("data", input.getBody()));
    }

    private String retrieveData() throws ConditionalCheckFailedException {
        initDynamo();
        Table table = dynamoDB.getTable(tableName);
        ItemCollection<ScanOutcome> items = table.scan();
        List<Map<String, Object>> listItem = new ArrayList<>();

        items.forEach(item -> {
            Map<String, Object> dataMap = item.asMap();
            BigDecimal currentTime = BigDecimal.valueOf(System.currentTimeMillis() / 1000);
            BigDecimal tsDiff = currentTime.subtract((BigDecimal) dataMap.get("ts"));
            
            //DEMO: uncomment this to change the time format
            //dataMap.put("tsdiff", tsDiff + " seconds ago");
            dataMap.put("tsdiff", calculateTime(tsDiff));
            
            listItem.add(dataMap);
        });
        ObjectMapper mapper = new ObjectMapper();
        String retStr = "";
        try {
            listItem.sort((i1, i2) -> ((BigDecimal) i1.get("ts")).compareTo((BigDecimal) i2.get("ts")));
            retStr = mapper.writeValueAsString(listItem);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return retStr;
    }

    private String getIndexFile() {
        FileInputStream fis;
        String retStr = "";
        try {
            fis = new FileInputStream("index.html");
            retStr = IOUtils.toString(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return retStr;
    }

    // avoid using static fields so we can load index page faster
    private void initDynamo() {
        if (client == null) {
            System.out.println("DEBUG xRayTracing ###: " + xRayTracing);
            if (xRayTracing == null || ! xRayTracing.equalsIgnoreCase("Active")) {                
                client = AmazonDynamoDBClientBuilder.standard().withRegion(regionName).build();
            } else {
                client = AmazonDynamoDBClientBuilder.standard().withRegion(regionName)
                    .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder())).build();
            }
        }
        if (dynamoDB == null) {
            dynamoDB = new DynamoDB(client);
        }
    }

    private String calculateTime(BigDecimal bseconds) {
        long totalSeconds = bseconds.longValue();
        int days = (int) TimeUnit.SECONDS.toDays(totalSeconds);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds) - (days * 24);
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) - (TimeUnit.SECONDS.toHours(totalSeconds) * 60);
        long seconds = TimeUnit.SECONDS.toSeconds(totalSeconds) - (TimeUnit.SECONDS.toMinutes(totalSeconds) * 60);

        String ret = seconds + " seconds ago";
        if (minutes > 0) {
            ret = minutes + " minutes " + ret;
        }
        if (hours > 0) {
            ret = hours + " hours " + ret;
        }
        if (days > 0) {
            ret = days + " days " + ret;
        }
        return ret;
    }
}
