package com.zoho.jw.schedule.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoho.jw.schedule.model.Access;
import com.zoho.jw.schedule.store.Store;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Owner-only access management for a congregation.
 *   GET    /congregations/{id}/access
 *   POST   /congregations/{id}/access   { "email": "...", "permissions": { "clm": {"view":true,"edit":false}, ... } }
 *   DELETE /congregations/{id}/access?email=...
 */
@RestController
@RequestMapping("/congregations/{id}/access")
public class AccessController
{
    public record GrantRequest(String email, JsonNode permissions, String nameEn) {}

    private final Store store;
    private final AccessGuard guard;

    public AccessController(Store store, AccessGuard guard)
    {
        this.store = store;
        this.guard = guard;
    }

    @GetMapping
    public List<Access> list(@RequestHeader(value = "X-User-Email", required = false) String email,
                             @PathVariable long id) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireOwner(caller, id);
        return store.accessForCongregation(id);
    }

    @PostMapping
    public Access grant(@RequestHeader(value = "X-User-Email", required = false) String email,
                        @PathVariable long id,
                        @RequestBody GrantRequest req) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireOwner(caller, id);
        if (req == null || req.email() == null || req.email().isBlank())
        {
            throw ApiException.badRequest("email is required");
        }
        JsonNode perms = req.permissions() != null ? req.permissions() : store.allAreasPermissions();
        return store.grantAccess(id, req.email().trim(), perms, req.nameEn());
    }

    @DeleteMapping
    public Map<String, Object> revoke(@RequestHeader(value = "X-User-Email", required = false) String email,
                                      @PathVariable long id,
                                      @RequestParam String targetEmail) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireOwner(caller, id);
        store.revokeAccess(id, targetEmail.trim());
        return Map.of("revoked", targetEmail);
    }
}
