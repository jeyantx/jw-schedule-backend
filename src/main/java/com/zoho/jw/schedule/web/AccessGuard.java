package com.zoho.jw.schedule.web;

import com.zoho.jw.schedule.model.Access;
import com.zoho.jw.schedule.model.Congregation;
import com.zoho.jw.schedule.store.Store;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Permission checks for the schedule endpoints.
 *
 * NOTE ON TRUST: the caller's identity comes from the {@code X-User-Email} header, which the
 * front-end sets from the Google sign-in. That is fine for the prototype but is NOT verified server
 * side yet — a caller could spoof the header. Hardening step (later): the front-end sends the Google
 * ID token, and this class verifies its signature/audience and reads the email from it. All the
 * permission logic below stays the same; only where {@code email} comes from changes.
 */
@Component
public class AccessGuard
{
    /** Kinds that are shared reference data (not tied to one schedule area). */
    private static final Set<String> SHARED_KINDS = Set.of("publishers", "groups", "meta", "settings");

    private final Store store;

    public AccessGuard(Store store)
    {
        this.store = store;
    }

    public String requireEmail(String email)
    {
        if (email == null || email.isBlank())
        {
            throw ApiException.unauthorized("Missing X-User-Email header.");
        }
        return email.trim();
    }

    public Congregation requireCongregation(long congregationId) throws Exception
    {
        return store.getCongregation(congregationId)
                .orElseThrow(() -> ApiException.notFound("No congregation " + congregationId));
    }

    /** The caller must have some access to the congregation; returns their access row. */
    public Access requireMember(String email, long congregationId) throws Exception
    {
        Access access = store.findAccess(congregationId, email);
        if (access == null)
        {
            throw ApiException.forbidden(email + " has no access to congregation " + congregationId);
        }
        return access;
    }

    /** Only the congregation owner may manage access / grant permissions. */
    public void requireOwner(String email, long congregationId) throws Exception
    {
        Congregation c = requireCongregation(congregationId);
        if (!email.equalsIgnoreCase(c.ownerEmail()))
        {
            throw ApiException.forbidden("Only the owner (" + c.ownerEmail() + ") can manage access.");
        }
    }

    public void requireView(String email, long congregationId, String kind) throws Exception
    {
        Access access = requireMember(email, congregationId);
        String area = areaForKind(kind);
        if (area == null) return; // shared kind: membership is enough to read
        if (!bool(access, area, "view"))
        {
            throw ApiException.forbidden("No view permission for '" + area + "'.");
        }
    }

    public void requireEdit(String email, long congregationId, String kind) throws Exception
    {
        Access access = requireMember(email, congregationId);
        Congregation c = requireCongregation(congregationId);
        if (email.equalsIgnoreCase(c.ownerEmail())) return; // owner can edit anything

        String area = areaForKind(kind);
        if (area == null)
        {
            // shared kind (publishers/groups/...) — allow if the user can edit any area
            for (String a : Store.AREAS)
            {
                if (bool(access, a, "edit")) return;
            }
            throw ApiException.forbidden("No edit permission (need edit on at least one area).");
        }
        if (!bool(access, area, "edit"))
        {
            throw ApiException.forbidden("No edit permission for '" + area + "'.");
        }
    }

    /** Non-throwing view check (used to filter the "load everything" response by area). */
    public boolean canView(String email, long congregationId, String kind) throws Exception
    {
        Access access = store.findAccess(congregationId, email);
        if (access == null) return false;
        String area = areaForKind(kind);
        return area == null || bool(access, area, "view");
    }

    /** Maps a data kind to its permission area, or null when the kind is shared reference data. */
    private static String areaForKind(String kind)
    {
        if (kind == null || SHARED_KINDS.contains(kind)) return null;
        return Store.AREAS.contains(kind) ? kind : null;
    }

    private static boolean bool(Access access, String area, String action)
    {
        return access.permissions().path(area).path(action).asBoolean(false);
    }
}
