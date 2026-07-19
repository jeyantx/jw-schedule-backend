package com.zoho.jw.schedule.catalyst;

import com.catalyst.config.ZCThreadLocal;
import com.zc.api.APIConstants;
import com.zc.common.ZCProject;
import com.zc.component.object.ZCObject;
import com.zc.component.object.ZCTable;
import com.zc.component.smartbrowz.ZCSmartBrowz;
import com.zc.component.stratus.ZCBucket;
import com.zc.component.stratus.ZCStratus;

/**
 * Single access point to Catalyst services. Builds the project once (from env vars) and hands out
 * service handles ({@link ZCSmartBrowz} for PDF, {@link ZCTable} for the data store). The SDK
 * authenticates internally — no access-token handling here.
 */
public final class Catalyst
{
    private static volatile ZCProject project;

    private Catalyst() {}

    /**
     * Mark the current thread as the admin caller for the Catalyst SDK. The SDK reads this from a
     * ThreadLocal, so any code touching Stratus/tables OFF a request thread (e.g. the wol batch
     * worker) must call this first — otherwise the per-thread context is missing.
     */
    public static void asAdmin()
    {
        ZCThreadLocal.putValue(APIConstants.USER_TYPE, "admin");
    }

    public static ZCSmartBrowz smartBrowz()
    {
        ZCThreadLocal.putValue(APIConstants.USER_TYPE, "admin");
        return ZCSmartBrowz.getInstance(project());
    }

    /** A handle to a data-store table by its numeric id. */
    public static ZCTable table(long tableId)
    {
        ZCThreadLocal.putValue(APIConstants.USER_TYPE, "admin");
        return ZCObject.getInstance(project()).getTableInstance(tableId);
    }

    /** A handle to a Stratus bucket by name. */
    public static ZCBucket bucket(String bucketName) throws Exception
    {
        ZCThreadLocal.putValue(APIConstants.USER_TYPE, "admin");
        return ZCStratus.getInstance(project()).bucketInstance(bucketName);
    }

    private static ZCProject project()
    {
        ZCProject p = project;
        if (p == null)
        {
            synchronized (Catalyst.class)
            {
                p = project;
                if (p == null)
                {
                    p = Project.build();
                    project = p;
                }
            }
        }
        return p;
    }
}
