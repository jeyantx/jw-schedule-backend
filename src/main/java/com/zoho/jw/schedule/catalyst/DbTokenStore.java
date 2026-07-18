package com.zoho.jw.schedule.catalyst;

import com.zc.auth.ZCAuth;
import com.zc.component.object.ZCRowObject;
import com.zc.component.object.ZCTable;

import java.util.List;

/**
 * Persists the Catalyst access token in the {@code TJW_Token} table so instances share one token and
 * refresh as a fleet. A single fixed key row ({@code NAME = "catalyst"}) holds the current token.
 *
 * Registered on {@link ZCAuth} at startup (see {@code AppSailMain}). NOTE: this does not remove the
 * one refresh per cold start — reading this table needs a token, so a brand-new instance bootstraps
 * with one refresh, then reuses/shares thereafter.
 */
public class DbTokenStore implements ZCAuth.TokenStore
{
    private static final String KEY = "catalyst";

    @Override
    public ZCAuth.SharedToken load() throws Exception
    {
        for (ZCRowObject r : table().getIterableRows())
        {
            if (KEY.equals(str(r, "NAME")))
            {
                String token = str(r, "ACCESS_TOKEN");
                if (token != null) return new ZCAuth.SharedToken(token, asLong(r.get("EXPIRES_AT")));
            }
        }
        return null;
    }

    @Override
    public void save(String accessToken, long expiresAt) throws Exception
    {
        ZCTable table = table();
        Long rowId = null;
        for (ZCRowObject r : table.getIterableRows())
        {
            if (KEY.equals(str(r, "NAME"))) { rowId = rowId(r); break; }
        }

        ZCRowObject row = ZCRowObject.getInstance();
        row.set("ACCESS_TOKEN", accessToken);
        row.set("EXPIRES_AT", expiresAt);
        if (rowId != null)
        {
            row.set("ROWID", rowId);
            table.updateRows(List.of(row));
        }
        else
        {
            row.set("NAME", KEY);
            table.insertRow(row);
        }
    }

    private static ZCTable table()
    {
        return Catalyst.table(TableIds.idFor(TableIds.TOKEN));
    }

    private static String str(ZCRowObject r, String col)
    {
        Object v = r.get(col);
        return v == null ? null : String.valueOf(v);
    }

    private static long asLong(Object v)
    {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v).trim());
    }

    private static long rowId(ZCRowObject r)
    {
        Object id = r.getRowObject().get("ROWID");
        if (id == null) id = r.get("ROWID");
        return asLong(id);
    }
}
