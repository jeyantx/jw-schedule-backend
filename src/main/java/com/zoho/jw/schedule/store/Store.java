package com.zoho.jw.schedule.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zc.component.object.ZCRowObject;
import com.zc.component.object.ZCTable;
import com.zoho.jw.schedule.catalyst.Catalyst;
import com.zoho.jw.schedule.catalyst.Stratus;
import com.zoho.jw.schedule.catalyst.TableIds;
import com.zoho.jw.schedule.model.Access;
import com.zoho.jw.schedule.model.Congregation;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All data-store operations against the three {@code TJW_*} tables, kept deliberately small:
 *
 *   TJW_Congregation : NAME, CODE, OWNER_EMAIL, CREATED_AT
 *   TJW_Access       : CONGREGATION_ID, EMAIL, PERMISSIONS(json text), CREATED_AT
 *   TJW_Data         : CONGREGATION_ID, KIND, PAYLOAD(json text), UPDATED_AT
 *
 * The schedule content itself lives as opaque JSON documents in TJW_Data (one row per
 * congregation+kind). The backend never needs to understand the schedule shape — the front-end owns
 * it — which is exactly what makes this a drop-in for the browser's localStorage model.
 */
@Service
public class Store
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** Areas that carry independent view/edit permissions. */
    public static final List<String> AREAS =
            List.of("clm", "weekend", "av", "cleaning", "fsm", "attendant");

    // ---- Congregations -----------------------------------------------------

    public Congregation createCongregation(String name, String ownerEmail) throws Exception
    {
        long now = System.currentTimeMillis();
        String code = newCode();

        ZCRowObject row = ZCRowObject.getInstance();
        row.set("NAME", name);
        row.set("CODE", code);
        row.set("OWNER_EMAIL", ownerEmail);
        row.set("CREATED_AT", now);
        ZCRowObject saved = table(TableIds.CONGREGATION).insertRow(row);
        long id = rowId(saved);

        // The creator is the owner and gets full view+edit on every area.
        grantAccess(id, ownerEmail, allAreasPermissions());

        return new Congregation(id, name, code, ownerEmail, now);
    }

    public Optional<Congregation> getCongregation(long id) throws Exception
    {
        return table(TableIds.CONGREGATION).getIterableRows().stream()
                .filter(r -> rowId(r) == id)
                .findFirst()
                .map(Store::toCongregation);
    }

    // ---- Access ------------------------------------------------------------

    /** All congregations this email can touch (used by GET /me). */
    public List<Access> accessForEmail(String email) throws Exception
    {
        List<Access> out = new ArrayList<>();
        for (ZCRowObject r : table(TableIds.ACCESS).getIterableRows())
        {
            if (email.equalsIgnoreCase(str(r, "EMAIL"))) out.add(toAccess(r));
        }
        return out;
    }

    public List<Access> accessForCongregation(long congregationId) throws Exception
    {
        List<Access> out = new ArrayList<>();
        for (ZCRowObject r : table(TableIds.ACCESS).getIterableRows())
        {
            if (asLong(r.get("CONGREGATION_ID")) == congregationId) out.add(toAccess(r));
        }
        return out;
    }

    public Access findAccess(long congregationId, String email) throws Exception
    {
        return accessForCongregation(congregationId).stream()
                .filter(a -> email.equalsIgnoreCase(a.email()))
                .findFirst().orElse(null);
    }

    /** Grant or update one user's permissions on a congregation (owner action). */
    public Access grantAccess(long congregationId, String email, JsonNode permissions) throws Exception
    {
        String permJson = JSON.writeValueAsString(permissions);
        Access existing = findAccess(congregationId, email);

        ZCTable table = table(TableIds.ACCESS);
        if (existing != null)
        {
            ZCRowObject row = ZCRowObject.getInstance();
            row.set("ROWID", existing.id());
            row.set("PERMISSIONS", permJson);
            table.updateRows(List.of(row));
            return new Access(existing.id(), congregationId, email, permissions);
        }

        ZCRowObject row = ZCRowObject.getInstance();
        row.set("CONGREGATION_ID", congregationId);
        row.set("EMAIL", email);
        row.set("PERMISSIONS", permJson);
        row.set("CREATED_AT", System.currentTimeMillis());
        ZCRowObject saved = table.insertRow(row);
        return new Access(rowId(saved), congregationId, email, permissions);
    }

    public void revokeAccess(long congregationId, String email) throws Exception
    {
        Access existing = findAccess(congregationId, email);
        if (existing != null) table(TableIds.ACCESS).deleteRow(existing.id());
    }

    // ---- Schedule data ------------------------------------------------------
    // The JSON document lives in a Stratus bucket; TJW_Data only holds its PATH.

    /** Stratus object key for a congregation+kind. */
    private static String objectPath(long congregationId, String kind)
    {
        return "congregations/" + congregationId + "/" + kind + ".json";
    }

    /** Payload JSON for one kind (read from Stratus), or null if none stored yet. */
    public String getData(long congregationId, String kind) throws Exception
    {
        String path = dataPath(congregationId, kind);
        return path == null ? null : Stratus.get(path);
    }

    /** Every kind for a congregation, as kind -> payload JSON (used for the initial load). */
    public Map<String, String> getAllData(long congregationId) throws Exception
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (ZCRowObject r : table(TableIds.DATA).getIterableRows())
        {
            if (asLong(r.get("CONGREGATION_ID")) == congregationId)
            {
                String payload = Stratus.get(str(r, "PATH"));
                if (payload != null) out.put(str(r, "KIND"), payload);
            }
        }
        return out;
    }

    /** Write the JSON document to Stratus, then upsert its PATH row in TJW_Data. */
    public void putData(long congregationId, String kind, String payloadJson) throws Exception
    {
        String path = objectPath(congregationId, kind);
        Stratus.put(path, payloadJson);   // the document itself goes to object storage

        ZCTable table = table(TableIds.DATA);
        Long existingRowId = null;
        for (ZCRowObject r : table.getIterableRows())
        {
            if (asLong(r.get("CONGREGATION_ID")) == congregationId && kind.equals(str(r, "KIND")))
            {
                existingRowId = rowId(r);
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (existingRowId != null)
        {
            ZCRowObject row = ZCRowObject.getInstance();
            row.set("ROWID", existingRowId);
            row.set("PATH", path);
            row.set("UPDATED_AT", now);
            table.updateRows(List.of(row));
        }
        else
        {
            ZCRowObject row = ZCRowObject.getInstance();
            row.set("CONGREGATION_ID", congregationId);
            row.set("KIND", kind);
            row.set("PATH", path);
            row.set("UPDATED_AT", now);
            table.insertRow(row);
        }
    }

    /** The stored Stratus path for a congregation+kind, or null. */
    private String dataPath(long congregationId, String kind) throws Exception
    {
        for (ZCRowObject r : table(TableIds.DATA).getIterableRows())
        {
            if (asLong(r.get("CONGREGATION_ID")) == congregationId && kind.equals(str(r, "KIND")))
            {
                return str(r, "PATH");
            }
        }
        return null;
    }

    // ---- Permissions helpers ----------------------------------------------

    /** A full-access permission map (every area view+edit) — used for the owner. */
    public JsonNode allAreasPermissions()
    {
        var root = JSON.createObjectNode();
        for (String area : AREAS)
        {
            var node = root.putObject(area);
            node.put("view", true);
            node.put("edit", true);
        }
        return root;
    }

    // ---- low-level helpers -------------------------------------------------

    private static ZCTable table(String envVar)
    {
        return Catalyst.table(TableIds.idFor(envVar));
    }

    private static Congregation toCongregation(ZCRowObject r)
    {
        return new Congregation(rowId(r), str(r, "NAME"), str(r, "CODE"),
                str(r, "OWNER_EMAIL"), asLong(r.get("CREATED_AT")));
    }

    private static Access toAccess(ZCRowObject r)
    {
        JsonNode perms;
        try { perms = JSON.readTree(str(r, "PERMISSIONS")); }
        catch (Exception e) { perms = JSON.createObjectNode(); }
        return new Access(rowId(r), asLong(r.get("CONGREGATION_ID")), str(r, "EMAIL"), perms);
    }

    private static long rowId(ZCRowObject r)
    {
        Object id = r.getRowObject().get("ROWID");
        if (id == null) id = r.get("ROWID");
        return asLong(id);
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

    private static String newCode()
    {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        return sb.toString();
    }
}
