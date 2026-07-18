package com.zoho.jw.schedule.web;

import com.zoho.jw.schedule.model.Congregation;
import com.zoho.jw.schedule.store.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create and read congregations.
 *   POST /congregations   { "name": "..." }  (creator = X-User-Email, becomes owner with full access)
 *   GET  /congregations/{id}
 */
@RestController
@RequestMapping("/congregations")
public class CongregationController
{
    public record CreateRequest(String name) {}

    private final Store store;
    private final AccessGuard guard;

    public CongregationController(Store store, AccessGuard guard)
    {
        this.store = store;
        this.guard = guard;
    }

    @PostMapping
    public Congregation create(@RequestHeader(value = "X-User-Email", required = false) String email,
                               @RequestBody CreateRequest req) throws Exception
    {
        String owner = guard.requireEmail(email);
        if (req == null || req.name() == null || req.name().isBlank())
        {
            throw ApiException.badRequest("name is required");
        }
        return store.createCongregation(req.name().trim(), owner);
    }

    @GetMapping("/{id}")
    public Congregation get(@RequestHeader(value = "X-User-Email", required = false) String email,
                            @PathVariable long id) throws Exception
    {
        String caller = guard.requireEmail(email);
        guard.requireMember(caller, id);
        return guard.requireCongregation(id);
    }
}
