package net.coreprotect.fabric.database;

import net.minecraft.core.BlockPos;

/**
 * Parsed lookup/rollback/restore parameters (CoreProtect style: u:, t:, a:, r:, b:, e:, p:).
 */
public final class Criteria {
    /** Exact user name, or {@code null} for all users. */
    public String user;
    /**
     * Lower bound of the time window (unix seconds); {@code 0} disables the filter. The purge reads
     * it the other way round: it deletes {@code time < Criteria.time}, i.e. everything older than
     * the cutoff, and refuses to run when it is {@code 0}.
     */
    public long time;
    /**
     * Action filter such as {@code block}, {@code +block}, {@code -block}, {@code container},
     * {@code item}, {@code sign}, {@code kill}, {@code death} or a {@code #<cause>} query
     * ({@code #tnt}, {@code #fire}). The value also selects which log table answers a lookup: with
     * {@code a:container} the block, item, sign and entity queries return no rows. {@code block},
     * {@code +block}, {@code -block}, a {@code #<cause>} query and an unset action do not narrow the
     * other tables, because {@code /co rollback} queries all of them with the parser default
     * {@code block}.
     */
    public String action;
    /** Lookup/rollback radius in blocks; {@code 0} disables it. */
    public int radius;
    /**
     * Material filter, matched case-insensitively as a substring. For block rows it applies to the
     * old/new block state; for container, item and entity rows to their data row (the accessed item,
     * the entity type). Signs store text only and warn that b: cannot be applied.
     */
    public String block;
    /**
     * Value to exclude. Block rows exclude a matching user, action/cause or block state; container,
     * item and entity rows exclude a matching user or item/entity type; chat, commands and sessions
     * exclude the user only (they carry no material to match).
     */
    public String exclude;
    /** Result page, starting at 1. */
    public int page = 1;
    /** Center used for the radius check (usually the command executor's position). */
    public BlockPos center;
    /**
     * Dimension id (e.g. {@code minecraft:overworld}) the radius filter is scoped to. Without
     * it a radius matches the same coordinates in every dimension. {@code null} = no world scope
     * (console commands without a position, single-block history lookups).
     */
    public String world;
}
