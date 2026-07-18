package com.zoho.jw.schedule;

import com.zc.auth.ZCAuth;
import com.zoho.jw.schedule.catalyst.DbTokenStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Collections;

/**
 * AppSail entrypoint. Catalyst AppSail runs {@code java -jar <bootJar>} and injects the listen port
 * via {@code X_ZOHO_CATALYST_LISTEN_PORT}. Keep this tiny — the real work is in the controllers.
 */
@SpringBootApplication
public class AppSailMain
{
    public static void main(String[] args)
    {
        String port = System.getenv().getOrDefault("X_ZOHO_CATALYST_LISTEN_PORT", "3000");
        SpringApplication app = new SpringApplication(AppSailMain.class);
        app.setDefaultProperties(Collections.singletonMap("server.port", port));
        app.run(args);

        // Share the Catalyst access token across instances/restarts via the TJW_Token table, so the
        // fleet refreshes as one instead of each minting its own.
        ZCAuth.setTokenStore(new DbTokenStore());
    }
}
