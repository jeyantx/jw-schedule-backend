package com.zoho.jw.schedule.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoho.jw.schedule.store.Store;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The schedule documents, stored as opaque JSON per {@code kind}
 * (publishers, groups, clm, weekend, av, cleaning, fsm, attendant, meta).
 *
 *   GET /congregations/{id}/data            -> { kind: <json>, ... }  (only kinds the caller may view)
 *   GET /congregations/{id}/data/{kind}     -> <json> for that kind (or 204 if none yet)
 *   PUT /congregations/{id}/data/{kind}     body = <json>  (requires edit on that area)
 */
@RestController
@RequestMapping("/congregations/{id}/data")
public class DataController
{
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Store store;
    private final AccessGuard guard;

    public DataController(Store store, AccessGuard guard)
    {
        this.store = store;
        this.guard = guard;
    }

    @GetMapping
    public Map<String, JsonNode> all(@RequestHeader(value = "X-User-Email", required = false) String email,
                                     @PathVariable long id) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireMember(caller, id);

        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : store.getAllData(id).entrySet())
        {
            if (guard.canView(caller, id, e.getKey()))
            {
                out.put(e.getKey(), parse(e.getValue()));
            }
        }
        return out;
    }

    @GetMapping("/{kind}")
    public JsonNode get(@RequestHeader(value = "X-User-Email", required = false) String email,
                        @PathVariable long id,
                        @PathVariable String kind) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireView(caller, id, kind);
        String payload = store.getData(id, kind);
        return payload == null ? null : parse(payload);
    }

    @PutMapping(value = "/{kind}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> put(@RequestHeader(value = "X-User-Email", required = false) String email,
                                   @PathVariable long id,
                                   @PathVariable String kind,
                                   @RequestBody String body) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireEdit(caller, id, kind);
        if (body == null || body.isBlank())
        {
            throw ApiException.badRequest("body (json) is required");
        }
        try
        {
            JSON.readTree(body); // validate it is JSON before storing
        }
        catch (Exception e)
        {
            throw ApiException.badRequest("body must be valid JSON: " + e.getMessage());
        }
        store.putData(id, kind, body);
        return Map.of("saved", kind, "bytes", body.length());
    }

    private static JsonNode parse(String json)
    {
        try { return JSON.readTree(json); }
        catch (Exception e) { return JSON.getNodeFactory().textNode(json); }
    }
}
