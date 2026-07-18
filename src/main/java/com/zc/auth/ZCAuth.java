//$Id$
package com.zc.auth;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.zc.api.APIConstants;
import com.zc.api.APIConstants.RequestMethod;
import com.zc.api.APIConstants.ZCUserScope;
import com.zc.api.APIRequest;
import com.zc.api.APIResponse;
import com.zc.exception.ZCClientException;
import com.zc.framework.ProjectMap;
import com.zc.validators.PreValidation;


public class ZCAuth {
	
	private static ZCAuth catalystAuth = null;
	
	private JSONObject oAuthParams ;
	
	static ZCAuthParam paramTokens ;

	private ZCUserScope scope = null;

	/** Guards all token reads/refreshes — {@link #paramTokens} is static and shared across the
	 *  snapshot-poller / request-worker threads that concurrently call {@link #getAccessToken()}.
	 *  Without this, threads raced the refresh and some read a half-updated / stale token → UnAuthorized. */
	private static final Object TOKEN_LOCK = new Object();

	/** Refresh this many ms BEFORE the server-side expiry. A token judged "valid" locally at
	 *  {@code now == expiresIn} is frequently already rejected server-side (clock skew + in-flight
	 *  latency), which produced the bursty UnAuthorized failures right at the ~1h expiry boundary. */
	private static final long EXPIRY_BUFFER_MS = 60_000L;

	/**
	 * Optional shared token store (e.g. a DB table) so instances reuse one access token and refresh
	 * as a fleet instead of each minting its own. Registered by the app at startup via
	 * {@link #setTokenStore}. Reading/writing the store itself calls Catalyst, which re-enters
	 * {@link #getAccessToken()} — {@link #IN_STORE} guards that so it never recurses into the store.
	 */
	public interface TokenStore
	{
		SharedToken load() throws Exception;
		void save(String accessToken, long expiresAt) throws Exception;
	}

	/** A token + its absolute expiry (epoch ms), as read from / written to the shared store. */
	public static final class SharedToken
	{
		public final String accessToken;
		public final long expiresAt;
		public SharedToken(String accessToken, long expiresAt)
		{
			this.accessToken = accessToken;
			this.expiresAt = expiresAt;
		}
	}

	private static volatile TokenStore tokenStore;
	private static final ThreadLocal<Boolean> IN_STORE = new ThreadLocal<>();

	public static void setTokenStore(TokenStore store) { tokenStore = store; }

	public ZCAuth(JSONObject oAuthParams)
	{
		this.oAuthParams = oAuthParams;
	}
	
	public static ZCAuth getMyInstance(JSONObject oAuthParams)
	{
		return getInstance(oAuthParams);
	}
	
	public static ZCAuth getInstance(JSONObject oAuthParams)
	{
		catalystAuth = new ZCAuth(oAuthParams);
		paramTokens = ZCAuthParam.getInstance(oAuthParams);
		return catalystAuth;
	}
	
	public static ZCAuth getInstance(File jsonFile)
	{
		JSONParser parser = new JSONParser();
        try
        {
            Object object = parser
                    .parse(new FileReader(jsonFile));
            JSONObject oAuthParams = (JSONObject)object;
            return getInstance(oAuthParams);
        }catch(Exception e)
        {
        	throw new IllegalArgumentException();
        }
	}
	
	public static ZCAuth getInstance(String jsonFilePath) throws ZCClientException 
	{
		  File jsonFile = new File(jsonFilePath);
		  if(jsonFile.exists())
		  {
			  try
			  {
				  JSONParser parser = new JSONParser();
		            Object object = parser
		                    .parse(new FileReader(jsonFile));
		            JSONObject oAuthParams = (JSONObject)object;
		         return getInstance(oAuthParams);
			  }catch(Exception e)
			  {
				  throw new ZCClientException("Invalid Json File");
			  } 
		  }
		  else
		  {
			  throw new ZCClientException("File","File not Found");
		  }
	}

	public ZCUserScope getScope() {
		return this.scope;
	}

	public ZCAuth setScope(ZCUserScope scope) {
		this.scope = scope;
		return this;
	}
	
	public Boolean isTicketAuthenticationEnabled()
	{
		if(oAuthParams != null)
		{
			if(oAuthParams.containsKey(APIConstants.TICKET))
			{
				return true;
			}
			return false;
		}
		
		return false;
	}
	
	public String getClientId()
	{
	   return paramTokens.getClientId();	
	}
	
	public String getClientSecret()
	{
	   return paramTokens.getClientSecret();	
	}
	
	public String getRedirectURL()
	{
		if(oAuthParams.containsKey("redirect_uri"))
		  {
			  return (String) oAuthParams.get("redirect_uri");
		  }
		  else 
		  {
			  return null;
		  }
	}
	
	public String getIAMURL()
	{
		return APIConstants.ACCOUNTS_URL;
	}
	
	public String getLoginWithZohoUrl()
	{
	   return getIAMURL() + "/oauth/v2/auth?scope=" + paramTokens.getScope() + "&client_id=" + paramTokens.getClientId() + "&client_secret=" + paramTokens.getClientSecret() + "&response_type=code&access_type=" + "offline" + "&redirect_uri=" + getRedirectURL();
	}
	
	public String getRefreshTokenURL()
	{
		return getIAMURL() + "/oauth/v2/token";
	}
	
	public String getTokenURL(){
		
		return getIAMURL() + "/oauth/v2/token";
	}
	
	public ZCAuthParam generateAccessToken(String code) throws Exception
	{
		if (code == null)
		{
		  throw new ZCClientException("Grant Token is not provided.");
		}
		try
		{
		  APIRequest request = new APIRequest();
		  HashMap<String, Object> params = new HashMap<>();
		  params.put("grant_type", "authorization_code");
		  params.put("code", code);
		  params.put("client_id", paramTokens.getClientId());
		  params.put("client_secret",paramTokens.getClientSecret());
		  request.setPostData(params);
		  request.setRequestMethod(RequestMethod.POST);
		  request.setUrl(getTokenURL());
		  request.setAuthNeeded(false);
		  APIResponse response = request.getResponse();
		  JSONParser parser = new JSONParser();
		  JSONObject responseJSON = (JSONObject) response.getResponseJSON().get(0);
		  if (responseJSON.containsKey("access_token"))
		  {
			  paramTokens.setAccessToken((String) responseJSON.get("access_token"));
			  paramTokens.setRefreshToken((String) responseJSON.get("refresh_token"));
			  paramTokens.setExpiresIn(System.currentTimeMillis() + ((Long) responseJSON.get("expires_in")) * 1000);
			  return paramTokens;
		  }
		  else
		  {
			  throw new ZCClientException("Error while generating Access token from Code");
		  }
		}catch(Exception e)
		{
			throw new ZCClientException("Error while generating Access token");
		}
	}
	
	public void refreshAccessToken(String refreshToken) throws Exception
	{
		PreValidation.checkNotNull(refreshToken, "Refresh token is not provided.");
		try
		{
		  APIRequest request = new APIRequest();
		  HashMap<String, Object> params = new HashMap<>();
		  params.put("grant_type", "refresh_token");
		  params.put("refresh_token", refreshToken);
		  params.put("client_id", paramTokens.getClientId());
		  params.put("client_secret",paramTokens.getClientSecret());
		  request.setPostData(params);
		  request.setRequestMethod(RequestMethod.POST);
		  request.setUrl(getRefreshTokenURL());
		  request.setAuthNeeded(false);
		  APIResponse response = request.getResponse();
		  JSONParser parser = new JSONParser();
		  JSONObject responseJSON = (JSONObject) response.getResponseJSON().get(0);
		  if (responseJSON.containsKey("access_token"))
		  {
		    paramTokens.setAccessToken((String) responseJSON.get("access_token"));
		    paramTokens.setRefreshToken(refreshToken);
		    paramTokens.setExpiresIn(System.currentTimeMillis()+ ((Long)responseJSON.get("expires_in"))*1000);
		  }
		  else
		  {
			  throw new ZCClientException("Exception while fetching access token from refresh token - " + responseJSON); //No I18N 
		  }
		}
		catch (Exception ex)
		{
		  throw new ZCClientException(ex);
		}
	}
	
	public String getAccessToken() throws Exception
	{
		if(paramTokens.getClientId() == null){
			return getDefaultAccessToken();
		}
		synchronized (TOKEN_LOCK)
		{
			long now = System.currentTimeMillis();

			// Nested call while we're reading/writing the shared store (that I/O hits Catalyst, which
			// re-enters here). Never recurse into the store — hand back the current token, refreshing
			// only if we somehow hold none.
			if (Boolean.TRUE.equals(IN_STORE.get()))
			{
				if (hasUsableToken(now)) return paramTokens.getAccessToken();
				refreshAccessToken(paramTokens.getRefreshToken());
				return paramTokens.getAccessToken();
			}

			// 1. In-memory token still comfortably valid.
			if (hasFreshToken(now)) return paramTokens.getAccessToken();

			// 2. Expiring but still usable to authenticate a read: another instance may have already
			//    refreshed and shared a newer token — adopt it and skip our own refresh.
			if (tokenStore != null && hasUsableToken(now))
			{
				SharedToken shared = loadShared();
				if (shared != null && shared.accessToken != null
						&& shared.expiresAt - EXPIRY_BUFFER_MS > now)
				{
					paramTokens.setAccessToken(shared.accessToken);
					paramTokens.setExpiresIn(shared.expiresAt);
					return shared.accessToken;
				}
			}

			// 3. Bootstrap (cold start) or shared token also stale: refresh, then publish for others.
			refreshAccessToken(paramTokens.getRefreshToken());
			saveShared();
			return paramTokens.getAccessToken();
		}
	}

	private static boolean hasFreshToken(long now)
	{
		Long e = paramTokens.getExpiresIn();
		return paramTokens.getAccessToken() != null && e != null && e - EXPIRY_BUFFER_MS > now;
	}

	private static boolean hasUsableToken(long now)
	{
		Long e = paramTokens.getExpiresIn();
		return paramTokens.getAccessToken() != null && e != null && e > now;
	}

	private static SharedToken loadShared()
	{
		try
		{
			IN_STORE.set(Boolean.TRUE);
			return tokenStore.load();
		}
		catch (Exception ignore) { return null; }   // store unavailable → caller refreshes
		finally { IN_STORE.remove(); }
	}

	private static void saveShared()
	{
		if (tokenStore == null) return;
		try
		{
			IN_STORE.set(Boolean.TRUE);
			tokenStore.save(paramTokens.getAccessToken(), paramTokens.getExpiresIn());
		}
		catch (Exception ignore) { }                 // best effort — other instances just refresh
		finally { IN_STORE.remove(); }
	}

	/**
	 * Force a token refresh regardless of the cached local expiry. The server rejected the current
	 * token with UnAuthorized even though our expiry clock still considered it valid (clock skew,
	 * early server-side expiry, a brief IAM hiccup, or a rotated token), so a plain retry would just
	 * reuse the same rejected token. Callers (e.g. the query-layer 401 retry) invoke this, then retry.
	 */
	public void forceRefresh() throws Exception
	{
		synchronized (TOKEN_LOCK)
		{
			refreshAccessToken(paramTokens.getRefreshToken());
			saveShared();
		}
	}

	/**
	 * Reflection-friendly static entry point for the query layer, which lives in an upstream module
	 * that compiles against the SDK jar's ZCAuth (no {@link #forceRefresh()}) but runs against this
	 * patched class. Invalidates + refreshes the shared token on the current singleton; a no-op if the
	 * project hasn't been initialised yet. Never throws to the caller — a failed refresh here just
	 * means the following retry reuses the old token and fails as before.
	 */
	public static void invalidateToken()
	{
		try
		{
			if (catalystAuth != null)
			{
				catalystAuth.forceRefresh();
			}
		}
		catch (Exception ignore)
		{
			// best-effort — the retry will surface the real error if the refresh didn't help
		}
	}
	
	public String getDefaultAccessToken() throws Exception
	{
		PreValidation.checkArgument(!ProjectMap.getInstance().getIsDefault(), "Make sure you have Default Configurations while Initializing..");
		return paramTokens.getAccessToken();
	}
}
