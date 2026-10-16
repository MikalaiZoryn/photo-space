package com.photospace.workspaces;

/** A member's role in a workspace. Kept deliberately small; see SYSTEM_OVERVIEW.md §3. */
public enum WorkspaceRole {
    /** Can change workspace settings. The creator of a workspace is its owner. */
    OWNER,
    /** Can work with the workspace's records. */
    MEMBER
}
