package com.zoho.jw.schedule.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One user's access to one congregation. {@code permissions} is the per-area view/edit map, e.g.
 * {@code {"clm":{"view":true,"edit":true}, "av":{"view":true,"edit":false}, ...}}.
 */
public record Access(long id, long congregationId, String email, JsonNode permissions) {}
