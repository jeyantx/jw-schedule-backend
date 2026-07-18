package com.zoho.jw.schedule.catalyst;

/**
 * Numeric Catalyst table ids are project-specific, so they are NOT hardcoded — set them as env vars
 * once you create the {@code TJW_*} tables:
 *
 *   TJW_CONGREGATION_TABLE_ID   -> the TJW_Congregation table id
 *   TJW_ACCESS_TABLE_ID         -> the TJW_Access table id
 *   TJW_DATA_TABLE_ID           -> the TJW_Data table id
 */
public final class TableIds
{
    public static final String CONGREGATION = "TJW_CONGREGATION_TABLE_ID";
    public static final String ACCESS       = "TJW_ACCESS_TABLE_ID";
    public static final String DATA         = "TJW_DATA_TABLE_ID";
    public static final String TOKEN        = "TJW_TOKEN_TABLE_ID";

    private TableIds() {}

    public static long idFor(String envVar)
    {
        String v = System.getenv(envVar);
        if (v == null || v.isBlank())
        {
            throw new IllegalStateException(
                    "Table id env var '" + envVar + "' is not set. Create the table in Catalyst and "
                    + "set this to its numeric id.");
        }
        try
        {
            return Long.parseLong(v.trim());
        }
        catch (NumberFormatException e)
        {
            throw new IllegalStateException("Env var '" + envVar + "' must be the numeric table id, got: " + v);
        }
    }
}
