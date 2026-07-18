package com.zoho.jw.schedule.catalyst;

import com.zc.component.stratus.ZCBucket;
import com.zc.component.stratus.beans.ZCPutObjectOptions;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stores the schedule JSON documents as objects in a Stratus bucket (object storage), instead of in
 * a DB Text column. The DB (TJW_Data) only keeps the object PATH. Bucket name from env
 * {@code TJW_STRATUS_BUCKET}.
 */
public final class Stratus
{
    public static final String BUCKET_ENV = "TJW_STRATUS_BUCKET";

    private static volatile ZCBucket cached;

    private Stratus() {}

    /** Create-or-replace an object with JSON content (atomic overwrite). */
    public static void put(String path, String json) throws Exception
    {
        ZCPutObjectOptions options = ZCPutObjectOptions.getInstance();
        options.setOverwrite("true");            // atomic create-or-replace
        options.setContentType("application/json");
        bucket().putObject(path, json, options);
    }

    /** Read an object's content as a UTF-8 string, or null if it doesn't exist. */
    public static String get(String path) throws Exception
    {
        ZCBucket bucket = bucket();
        if (Boolean.FALSE.equals(bucket.headObject(path))) return null;
        try (InputStream in = bucket.getObject(path))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void delete(String path) throws Exception
    {
        bucket().deleteObject(path);
    }

    private static ZCBucket bucket() throws Exception
    {
        ZCBucket b = cached;
        if (b == null)
        {
            synchronized (Stratus.class)
            {
                b = cached;
                if (b == null)
                {
                    b = Catalyst.bucket(bucketName());
                    cached = b;
                }
            }
        }
        return b;
    }

    private static String bucketName()
    {
        String v = System.getenv(BUCKET_ENV);
        if (v == null || v.isBlank())
        {
            throw new IllegalStateException(
                    "Env var '" + BUCKET_ENV + "' is not set. Create a Stratus bucket and set this to its name.");
        }
        return v.trim();
    }
}
