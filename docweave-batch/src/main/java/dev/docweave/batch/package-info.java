/**
 * Bounded virtual-thread processing for blocking document-file work.
 * Each task gets a fresh virtual thread, but at most the configured window is outstanding.
 * Results retain input order. Legacy concurrency-only calls observe failures in input order and
 * wait for executor shutdown. Optional task/batch deadlines observe failures independently,
 * interrupt outstanding work and return without indefinite cleanup waits. Operations ignoring
 * interruption may outlive timed or incremental calls; deadlines cannot forcibly terminate threads.
 * Incremental callbacks run sequentially on the caller, with lazy inputs and bounded outstanding
 * results. Delivered prefixes survive later failure; input resources remain caller-owned.
 */
package dev.docweave.batch;
