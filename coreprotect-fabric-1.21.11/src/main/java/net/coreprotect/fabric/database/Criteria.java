package net.coreprotect.fabric.database;

import net.minecraft.util.math.BlockPos;

/**
 * Parsed lookup/rollback/restore parameters (CoreProtect style: u:, t:, a:, r:, b:, e:, p:).
 */
public final class Criteria {
    /** Exact user name, or {@code null} for all users. */
    public String user;
    /** Lower bound of the time window (unix seconds); {@code 0} disables the filter. */
    public long time;
    /** Action filter such as {@code block}, {@code +block}, {@code -block}, {@code #container}, {@code #kill}, {@code #fire}... */
    public String action;
    /** Lookup/rollback radius in blocks; {@code 0} disables it. */
    public int radius;
    /** Block filter (matched against the full block id, case-insensitive substring). */
    public String block;
    /** Action to exclude (e.g. {@code #fire}). */
    public String exclude;
    /** Result page, starting at 1. */
    public int page = 1;
    /** Center used for the radius check (usually the command executor's position). */
    public BlockPos center;
}
