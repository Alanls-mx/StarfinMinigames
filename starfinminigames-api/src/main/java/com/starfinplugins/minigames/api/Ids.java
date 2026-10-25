package com.starfinplugins.minigames.api;

final class Ids {
    private Ids() { }

    static String stable(String value, String name) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException(name + " must be a stable lower-case id (1-32 characters)");
        }
        return value;
    }

    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
