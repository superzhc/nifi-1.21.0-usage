/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws.s3;

import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClient;
import com.amazonaws.services.kms.model.CreateKeyRequest;
import com.amazonaws.services.kms.model.CreateKeyResult;
import com.amazonaws.services.kms.model.GenerateDataKeyRequest;
import com.amazonaws.services.kms.model.GenerateDataKeyResult;
import com.amazonaws.services.kms.model.ScheduleKeyDeletionRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.DeleteBucketRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.ObjectTagging;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.Tag;
import org.apache.nifi.util.file.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base class for S3 Integration Tests. Establishes a bucket and helper methods for creating test scenarios
 *
 * @see ITDeleteS3Object
 * @see ITFetchS3Object
 * @see ITPutS3Object
 * @see ITListS3
 */
public abstract class AbstractS3IT {
    protected final static String CREDENTIALS_FILE = System.getProperty("user.home") + "/aws-credentials.properties";
    protected final static String SAMPLE_FILE_RESOURCE_NAME = "/hello.txt";
    protected final static String REGION = System.getProperty("it.aws.region", "us-west-1");
    // Adding REGION to bucket prevents errors of
    //      "A conflicting conditional operation is currently in progress against this resource."
    // when bucket is rapidly added/deleted and consistency propagation causes this error.
    // (Should not be necessary if REGION remains static, but added to prevent future frustration.)
    // [see http://stackoverflow.com/questions/13898057/aws-error-message-a-conflicting-conditional-operation-is-currently-in-progress]
    protected final static String BUCKET_NAME = "test-bucket-" + System.currentTimeMillis() + "-" + REGION;

    // Static so multiple Tests can use same client
    protected static AmazonS3Client client;
    protected static AWSKMS kmsClient;

    @BeforeAll
    public static void oneTimeSetup() {
        // Creates a client and bucket for this test

        final FileInputStream fis;
        try {
            fis = new FileInputStream(CREDENTIALS_FILE);
        } catch (FileNotFoundException e1) {
            fail("Could not open credentials file " + CREDENTIALS_FILE + ": " + e1.getLocalizedMessage());
            return;
        }
        try {
            final PropertiesCredentials credentials = new PropertiesCredentials(fis);
            client = new AmazonS3Client(credentials);
            kmsClient = new AWSKMSClient(credentials);
            kmsClient.setRegion(Region.getRegion(Regions.fromName(REGION)));

            if (client.doesBucketExist(BUCKET_NAME)) {
                fail("Bucket " + BUCKET_NAME + " exists. Choose a different bucket name to continue test");
            }

            CreateBucketRequest request = REGION.contains("east")
                    ? new CreateBucketRequest(BUCKET_NAME) // See https://github.com/boto/boto3/issues/125
                    : new CreateBucketRequest(BUCKET_NAME, REGION);
            client.createBucket(request);

        } catch (final AmazonS3Exception e) {
            fail("Can't create the key " + BUCKET_NAME + ": " + e.getLocalizedMessage());
        } catch (final IOException e) {
            fail("Caught IOException preparing tests: " + e.getLocalizedMessage());
        } finally {
            FileUtils.closeQuietly(fis);
        }

        if (!client.doesBucketExist(BUCKET_NAME)) {
            fail("Setup incomplete, tests will fail");
        }
    }

    @AfterAll
    public static void oneTimeTearDown() {
        // Empty the bucket before deleting it.
        try {
            if (client == null) {
                return;
            }

            ObjectListing objectListing = client.listObjects(BUCKET_NAME);

            while (true) {
                for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
                    client.deleteObject(BUCKET_NAME, objectSummary.getKey());
                }

                if (objectListing.isTruncated()) {
                    objectListing = client.listNextBatchOfObjects(objectListing);
                } else {
                    break;
                }
            }

            DeleteBucketRequest dbr = new DeleteBucketRequest(BUCKET_NAME);
            client.deleteBucket(dbr);
        } catch (final AmazonS3Exception e) {
            System.err.println("Unable to delete bucket " + BUCKET_NAME + e.toString());
        }

        if (client.doesBucketExist(BUCKET_NAME)) {
            fail("Incomplete teardown, subsequent tests might fail");
        }
    }

    protected void putTestFile(String key, File file) throws AmazonS3Exception {
        PutObjectRequest putRequest = new PutObjectRequest(BUCKET_NAME, key, file);

        client.putObject(putRequest);
    }

    protected void putTestFileEncrypted(String key, File file) throws AmazonS3Exception, FileNotFoundException {
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        PutObjectRequest putRequest = new PutObjectRequest(BUCKET_NAME, key, new FileInputStream(file), objectMetadata);

        client.putObject(putRequest);
    }

    protected void putFileWithUserMetadata(String key, File file, Map<String, String> userMetadata) throws AmazonS3Exception, FileNotFoundException {
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setUserMetadata(userMetadata);
        PutObjectRequest putRequest = new PutObjectRequest(BUCKET_NAME, key, new FileInputStream(file), objectMetadata);

        client.putObject(putRequest);
    }

    protected void putFileWithObjectTag(String key, File file, List<Tag> objectTags) {
        PutObjectRequest putRequest = new PutObjectRequest(BUCKET_NAME, key, file);
        putRequest.setTagging(new ObjectTagging(objectTags));
        PutObjectResult result = client.putObject(putRequest);
    }

    protected Path getResourcePath(String resourceName) {
        Path path = null;

        try {
            path = Paths.get(getClass().getResource(resourceName).toURI());
        } catch (URISyntaxException e) {
           fail("Resource: " + resourceName + " does not exist" + e.getLocalizedMessage());
        }

        return path;
    }

    protected File getFileFromResourceName(String resourceName) {
        URI uri = null;
        try {
            uri = this.getClass().getResource(resourceName).toURI();
        } catch (URISyntaxException e) {
            fail("Cannot proceed without File : " + resourceName);
        }

        return new File(uri);
    }

    protected static String getKMSKey() {
        CreateKeyRequest cmkRequest = new CreateKeyRequest().withDescription("CMK for unit tests");
        CreateKeyResult cmkResult = kmsClient.createKey(cmkRequest);

        GenerateDataKeyRequest dekRequest = new GenerateDataKeyRequest().withKeyId(cmkResult.getKeyMetadata().getKeyId()).withKeySpec("AES_128");
        GenerateDataKeyResult dekResult = kmsClient.generateDataKey(dekRequest);

        return dekResult.getKeyId();
    }

    protected static void deleteKMSKey(String keyId) {
        ScheduleKeyDeletionRequest req = new ScheduleKeyDeletionRequest().withKeyId(keyId).withPendingWindowInDays(7);
        kmsClient.scheduleKeyDeletion(req);
    }
}