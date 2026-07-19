package com.zoho.jw.schedule.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One user's access to one congregation. {@code permissions} is the per-area view/edit map, e.g.
 * {@code {"clm":{"view":true,"edit":true}, "av":{"view":true,"edit":false}, ...}}.
 * {@code nameEn} is the member's English name (optional); it lets the app map a signed-in member
 * to their publisher record. It rides inside the same JSON as {@code permissions} (see Store), so
 * no extra data-store column is needed.
 */
public record Access(long id, long congregationId, String email, JsonNode permissions, String nameEn) {}
