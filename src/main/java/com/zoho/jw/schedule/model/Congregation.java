package com.zoho.jw.schedule.model;

/** A congregation (tenant). {@code code} is a short human-shareable reference. */
public record Congregation(long id, String name, String code, String ownerEmail, long createdAt) {}
