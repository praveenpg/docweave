package dev.docweave.validation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@code --name value} option parser for migrated scorers.
 *
 * <p>Options must be spelled in full; unknown, repeated or missing required options are rejected. A
 * multi-valued option consumes values until the next {@code --} token, as argparse {@code nargs='+'}.
 *
 * @param options single-valued options by name (without leading dashes)
 * @param lists multi-valued options by name
 * @param positionals positional arguments in order
 */
record ScoringArguments(Map<String, String> options, Map<String, List<String>> lists, List<String> positionals) {
    /**
     * Copies parsed values.
     *
     * @param options single-valued options by name (without leading dashes)
     * @param lists multi-valued options by name
     * @param positionals positional arguments in order
     */
    ScoringArguments {
        options = Map.copyOf(options);
        lists = Map.copyOf(lists);
        positionals = List.copyOf(positionals);
    }

    /**
     * Parses arguments.
     *
     * @param arguments raw arguments
     * @param required required single-valued option names
     * @param optional optional single-valued option names
     * @param multiple multi-valued option names
     * @param positionalCount exact number of positional arguments
     * @return parsed arguments
     * @throws IllegalArgumentException if arguments do not match the declaration
     */
    static ScoringArguments parse(final List<String> arguments, final Set<String> required, final Set<String> optional,
            final Set<String> multiple, final int positionalCount) {
        final var options = new LinkedHashMap<String, String>();
        final var lists = new LinkedHashMap<String, List<String>>();
        final var positionals = new ArrayList<String>();
        for (int index = 0; index < arguments.size(); index++) {
            final String argument = arguments.get(index);
            if (!argument.startsWith("--")) {
                positionals.add(argument);
                continue;
            }
            final String name = argument.substring(2);
            if (multiple.contains(name)) {
                final var values = new ArrayList<String>();
                while (index + 1 < arguments.size() && !arguments.get(index + 1).startsWith("--")) {
                    values.add(arguments.get(++index));
                }
                if (values.isEmpty() || lists.put(name, values) != null) {
                    throw new IllegalArgumentException("Expected one or more values once for --" + name);
                }
                continue;
            }
            if (!required.contains(name) && !optional.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name);
            }
            if (index + 1 >= arguments.size() || options.put(name, arguments.get(++index)) != null) {
                throw new IllegalArgumentException("Expected exactly one value for --" + name);
            }
        }
        for (final String name : required) {
            if (!options.containsKey(name)) {
                throw new IllegalArgumentException("Missing required option --" + name);
            }
        }
        if (positionals.size() != positionalCount) {
            throw new IllegalArgumentException("Expected " + positionalCount + " positional arguments");
        }
        return new ScoringArguments(options, lists, positionals);
    }

    /**
     * Returns a path option.
     *
     * @param name option name
     * @return path
     */
    Path path(final String name) {
        return Path.of(text(name));
    }

    /**
     * Returns a single-valued option.
     *
     * @param name option name
     * @return raw value
     */
    String text(final String name) {
        final String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing option --" + name);
        }
        return value;
    }
}
