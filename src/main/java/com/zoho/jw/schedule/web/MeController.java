package com.zoho.jw.schedule.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoho.jw.schedule.model.Access;
import com.zoho.jw.schedule.model.Congregation;
import com.zoho.jw.schedule.store.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /me} — the congregations the signed-in user can access, with their permissions. The
 * front-end calls this right after Google sign-in to know what to show.
 */
@RestController
public class MeController
{
    public record Membership(Congregation congregation, JsonNode permissions) {}

    private final Store store;
    private final AccessGuard guard;

    public MeController(Store store, AccessGuard guard)
    {
        this.store = store;
        this.guard = guard;
    }

    @GetMapping("/me")
    public List<Membership> me(@RequestHeader(value = "X-User-Email", required = false) String email) throws Exception
    {
        String caller = guard.requireEmail(email);
        List<Membership> out = new ArrayList<>();
        for (Access access : store.accessForEmail(caller))
        {
            store.getCongregation(access.congregationId())
                    .ifPresent(c -> out.add(new Membership(c, access.permissions())));
        }
        return out;
    }
}
