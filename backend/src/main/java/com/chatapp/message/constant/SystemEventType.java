package com.chatapp.message.constant;

/**
 * Constants for SYSTEM message event types (W7-D4).
 *
 * These values map to the system_event_type column in the messages table.
 * Each event type corresponds to a group management action.
 *
 * Contract: API_CONTRACT.md "MessageDto -- finalized shape (v1.2.0-w7-system)"
 */
public final class SystemEventType {

    public static final String GROUP_CREATED      = "GROUP_CREATED";
    public static final String MEMBER_ADDED       = "MEMBER_ADDED";
    public static final String MEMBER_REMOVED     = "MEMBER_REMOVED";
    public static final String MEMBER_LEFT        = "MEMBER_LEFT";
    public static final String ROLE_PROMOTED      = "ROLE_PROMOTED";
    public static final String ROLE_DEMOTED       = "ROLE_DEMOTED";
    public static final String OWNER_TRANSFERRED  = "OWNER_TRANSFERRED";
    public static final String GROUP_RENAMED      = "GROUP_RENAMED";

    private SystemEventType() {
        // Utility class — no instances
    }
}
