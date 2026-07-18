package com.zoho.jw.schedule.catalyst;

import com.catalyst.config.ZCThreadLocal;
import com.zc.api.APIConstants;
import com.zc.auth.ZCAuth;
import com.zc.common.ZCProject;
import com.zc.common.ZCProjectConfig;
import lombok.SneakyThrows;
import org.json.simple.JSONObject;

import java.util.logging.Logger;

/**
 * Builds the Catalyst {@link ZCProject} from environment variables — the same set hello-app uses.
 * The SDK then authenticates internally (refresh token → access token), so our code never handles
 * a token.
 *
 * Required env vars:
 *   CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, PROJECT_ID, PROJECT_KEY
 * Optional:
 *   PROJECT_DOMAIN (default https://api.catalyst.zoho.in), ENVIRONMENT (default Development),
 *   PROJECT_NAME (default JW-Schedule)
 *
 * OAuth scope needed for PDF generation: ZohoCatalyst.pdfshot.execute
 */
public final class Project
{
    private static final Logger LOGGER = Logger.getLogger(Project.class.getName());

    private Project() {}

    @SneakyThrows
    @SuppressWarnings("unchecked")
    public static ZCProject build()
    {
        ZCThreadLocal.putValue(APIConstants.USER_TYPE, "admin");

        JSONObject oAuthParams = new JSONObject();
        oAuthParams.put("client_id", requireEnv("CLIENT_ID"));
        oAuthParams.put("client_secret", requireEnv("CLIENT_SECRET"));
        oAuthParams.put("refresh_token", requireEnv("REFRESH_TOKEN"));
        oAuthParams.put("grant_type", "refresh_token");

        ZCAuth auth = ZCAuth.getInstance(oAuthParams);
        auth.setScope(APIConstants.ZCUserScope.ADMIN);

        String projectName = env("PROJECT_NAME", "JW-Schedule");
        long projectId = Long.parseLong(requireEnv("PROJECT_ID"));
        ZCProjectConfig config = ZCProjectConfig.newBuilder()
                .setProjectId(projectId)
                .setProjectKey(requireEnv("PROJECT_KEY"))
                .setZcAuth(auth)
                .setProjectDomain(env("PROJECT_DOMAIN", "https://api.catalyst.zoho.in"))
                .setEnvironment(env("ENVIRONMENT", "Development"))
                .build();

        LOGGER.info("Initializing Catalyst project '" + projectName + "' (id=" + projectId + ")");
        return ZCProject.initProject(config, true, projectName);
    }

    private static String env(String key, String defaultValue)
    {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    private static String requireEnv(String key)
    {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
        {
            throw new IllegalStateException("Required Catalyst env var '" + key + "' is not set.");
        }
        return v.trim();
    }
}
