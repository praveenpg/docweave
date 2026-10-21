package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Document-local definition lookup, separate from paragraph policy and mutable list counters.
 * Follows numStyleLink through numbering styles, then applies inner-to-outer instance overrides.
 * styleLink is descriptive, not a forward reference. Missing/cyclic links never invent a label.
 * Traversal is iterative, interruptible and limited to 128 hops per link/style chain.
 */
final class DocxNumberingDefinitions {
    /**
     * Effective numbering level definition and starting value.
     *
     * @param definition XMLBeans level definition
     * @param start effective starting counter value
     */
    record Level(CTLvl definition, BigInteger start) {}

    /**
     * Tagged identity prevents an abstract ID from colliding with an instance ID.
     *
     * @param id numbering instance ID or shared abstract numbering ID
     * @param sharedAbstract {@code true} when counters are shared by abstract numbering ID
     */
    record CounterKey(BigInteger id, boolean sharedAbstract) {}

    /**
     * Cache key combining numbering instance ID and 0-based level depth.
     *
     * @param id numbering instance ID
     * @param depth 0-based level depth
     */
    private record Key(BigInteger id, int depth) {}

    /** Parsed Word document. */
    private final XWPFDocument document;
    /** Cached effective level definitions keyed by numbering ID and depth. */
    private final Map<Key, Optional<Level>> cache = new HashMap<>();
    /** Cached counter sequence identities keyed by numbering instance ID. */
    private final Map<BigInteger, CounterKey> counterKeys = new HashMap<>();

    /**
     * Creates a numbering definition lookup for the given Word document.
     *
     * @param document parsed Word document
     */
    DocxNumberingDefinitions(final XWPFDocument document) {
        this.document = document;
    }

    /**
     * Shares unmodified, direct single-level decimal definitions, as observed in the
     * owned Word controls. Overrides, style-linked aliases and multilevel definitions
     * retain instance-local state; their cross-instance semantics are not established
     * by that evidence. A startOverride therefore creates an independent sequence.
     *
     * @param id numbering instance ID
     * @return counter sequence key for the numbering instance
     * @throws IOException if the thread is interrupted
     */
    CounterKey counterKey(BigInteger id) throws IOException {
        ElementCollector.checkInterrupted();
        final var saved = counterKeys.get(id);
        if (saved != null) {
            return saved;
        }
        var key = new CounterKey(id, false);
        final var numbering = document.getNumbering();
        final var instance = numbering == null ? null : numbering.getNum(id);
        // Share the abstract counter sequence only for unmodified, direct single-level decimal lists.
        if (instance != null && instance.getCTNum().getAbstractNumId() != null
                && instance.getCTNum().getLvlOverrideList().isEmpty()) {
            final var abstractId = instance.getCTNum().getAbstractNumId().getVal();
            final var abstractNum = numbering.getAbstractNum(abstractId);
            if (abstractNum != null) {
                var definition = abstractNum.getCTAbstractNum();
                if (!definition.isSetNumStyleLink() && !definition.isSetStyleLink()
                        && definition.sizeOfLvlArray() == 1) {
                    final var level = definition.getLvlArray(0);
                    if (BigInteger.ZERO.equals(level.getIlvl()) && level.isSetNumFmt()
                            && "decimal".equals(level.getNumFmt().getVal().toString())) {
                        key = new CounterKey(abstractId, true);
                    }
                }
            }
        }
        counterKeys.put(id, key);
        return key;
    }

    /**
     * Resolves and caches the effective numbering level definition for an instance ID and depth.
     *
     * @param id numbering instance ID
     * @param depth 0-based level depth
     * @return effective level definition, or {@code null} if unresolved
     * @throws IOException if the thread is interrupted
     */
    Level level(BigInteger id, int depth) throws IOException {
        ElementCollector.checkInterrupted();
        var key = new Key(id, depth);
        final var saved = cache.get(key);
        if (saved != null) {
            return saved.orElse(null);
        }
        final var value = resolve(id, depth);
        cache.put(key, Optional.ofNullable(value));
        return value;
    }

    /**
     * Finds the closest paragraph-style association on effective, overridden levels.
     *
     * @param id numbering instance ID
     * @param styles ordered candidate paragraph style IDs from most specific to least specific
     * @return associated 0-based level depth, or {@code null} if none matches
     * @throws IOException if the thread is interrupted
     */
    Integer associatedLevel(BigInteger id, final List<String> styles) throws IOException {
        // Check paragraph styles from most derived to base and match the first level whose pStyle references it.
        for (final String styleId : styles) {
            final var style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
            if (style == null
                    || style.getCTStyle().isSetType() && style.getCTStyle().getType() != STStyleType.PARAGRAPH) {
                continue;
            }
            for (int depth = 0; depth < 9; depth++) {
                final var level = level(id, depth);
                if (level != null && level.definition().isSetPStyle()
                        && styleId.equals(level.definition().getPStyle().getVal())) {
                    return depth;
                }
            }
        }
        return null;
    }

    /**
     * Follows {@code numStyleLink} chains and applies instance overrides to resolve an effective level.
     *
     * @param id numbering instance ID
     * @param depth 0-based level depth
     * @return resolved level definition, or {@code null} if missing or cyclic
     * @throws IOException if the thread is interrupted
     */
    private Level resolve(BigInteger id, int depth) throws IOException {
        final var numbering = document.getNumbering();
        if (numbering == null || id == null || id.signum() <= 0) {
            return null;
        }
        final var visited = new HashSet<BigInteger>();
        final var instances = new ArrayList<CTNum>();
        CTLvl definition = null;
        // Follow numStyleLink indirections up to 128 hops while recording traversed CTNum instances.
        for (int hops = 0; hops < 128; hops++) {
            ElementCollector.checkInterrupted();
            if (!visited.add(id)) {
                return null;
            }
            final var instance = numbering.getNum(id);
            if (instance == null || instance.getCTNum().getAbstractNumId() == null) {
                return null;
            }
            final var num = instance.getCTNum();
            instances.add(num);
            final var abstractNum = numbering.getAbstractNum(num.getAbstractNumId().getVal());
            if (abstractNum == null) {
                return null;
            }
            final var abstractDefinition = abstractNum.getCTAbstractNum();
            if (abstractDefinition.isSetNumStyleLink()) {
                id = styleNumber(abstractDefinition.getNumStyleLink().getVal());
                if (id == null || id.signum() <= 0) {
                    return null;
                }
                continue;
            }
            // Locate the base level definition for the requested depth on the terminal abstract numbering.
            for (final var candidate : abstractDefinition.getLvlList()) {
                if (BigInteger.valueOf(depth).equals(candidate.getIlvl())) {
                    definition = candidate;
                    break;
                }
            }
            BigInteger start = null;
            // Apply lvlOverride entries from innermost target back to outermost caller instance so outer wins.
            for (final var outer : instances.reversed()) {
                ElementCollector.checkInterrupted();
                for (final var override : outer.getLvlOverrideList()) {
                    if (!BigInteger.valueOf(depth).equals(override.getIlvl())) {
                        continue;
                    }
                    if (override.isSetLvl()) {
                        definition = override.getLvl();
                        start = null;
                    }
                    if (override.isSetStartOverride()) {
                        start = override.getStartOverride().getVal();
                    }
                }
            }
            if (definition == null) {
                return null;
            }
            return new Level(definition, start != null             ? start
                                         : definition.isSetStart() ? definition.getStart().getVal()
                                                                   : BigInteger.ONE);
        }
        return null;
    }

    /**
     * Resolves a numbering instance ID from a numbering style chain.
     *
     * @param id starting numbering style ID
     * @return resolved numbering instance ID, or {@code null} if unresolved or cyclic
     * @throws IOException if the thread is interrupted
     */
    private BigInteger styleNumber(String id) throws IOException {
        final var styles = document.getStyles();
        if (styles == null) {
            return null;
        }
        final var visited = new HashSet<String>();
        // Walk the numbering style inheritance chain until a concrete numId is found or a cycle/limit is hit.
        for (int hops = 0; id != null && hops < 128 && visited.add(id); hops++) {
            ElementCollector.checkInterrupted();
            final var style = styles.getStyle(id);
            if (style == null || style.getCTStyle().getType() != STStyleType.NUMBERING) {
                return null;
            }
            final var properties = style.getCTStyle().getPPr();
            if (properties != null && properties.isSetNumPr() && properties.getNumPr().isSetNumId()) {
                return properties.getNumPr().getNumId().getVal();
            }
            id = style.getBasisStyleID();
        }
        return null;
    }
}
