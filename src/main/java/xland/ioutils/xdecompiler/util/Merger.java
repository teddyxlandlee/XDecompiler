package xland.ioutils.xdecompiler.util;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public record Merger<E>(Consumer<? super E> firstOnlyConsumer,
                        Consumer<? super E> secondOnlyConsumer,
                        BiConsumer<? super E, ? super E> sharedConsumer) {
    public static <E, S> Merger<E> extraWhenSided(S stateMarkerFirst, S stateMarkerSecond,
                                                  BinaryOperator<E> chooser,
                                                  Consumer<? super E> publicConsumer,
                                                  BiConsumer<? super E, ? super S> sidedConsumer) {
        Objects.requireNonNull(chooser, "chooser");
        Objects.requireNonNull(publicConsumer, "publicConsumer");
        Objects.requireNonNull(sidedConsumer, "sidedConsumer");

        return new Merger<>(
                e -> {
                    publicConsumer.accept(e);
                    sidedConsumer.accept(e, stateMarkerFirst);
                },
                e -> {
                    publicConsumer.accept(e);
                    sidedConsumer.accept(e, stateMarkerSecond);
                },
                (e1, e2) -> publicConsumer.accept(chooser.apply(e1, e2)));
    }

    public static <E, S> Merger<E> specialWhenSided(S stateMarkerFirst, S stateMarkerSecond,
                                                    BiConsumer<? super E, ? super E> sharedConsumer,
                                                    BiConsumer<? super E, ? super S> sidedConsumer) {
        Objects.requireNonNull(sidedConsumer, "sidedConsumer");

        return new Merger<>(
                e -> sidedConsumer.accept(e, stateMarkerFirst),
                e -> sidedConsumer.accept(e, stateMarkerSecond),
                sharedConsumer
        );
    }

    public Merger {
        Objects.requireNonNull(firstOnlyConsumer, "firstOnlyConsumer");
        Objects.requireNonNull(secondOnlyConsumer, "secondOnlyConsumer");
        Objects.requireNonNull(sharedConsumer, "sharedConsumer");
    }

    public <K> void mergePreserveOrder(List<? extends E> first, List<? extends E> second, Function<E, K> keyExtractor) {
        if (first instanceof RandomAccess && second instanceof RandomAccess) {
            mergePreserveOrderRA(first, second, keyExtractor);
        } else {
            mergePreserveOrderNRA(first, second, keyExtractor);
        }
    }

    private <K> void mergePreserveOrderRA(
            List<? extends E> first,
            List<? extends E> second,
            Function<? super E, ? extends K> keyExtractor) {

        // Precompute key sets for membership test
        Map<K, E> firstEntries = first.stream().collect(Collectors.toMap(keyExtractor, Function.identity()));
        Map<K, E> secondEntries = second.stream().collect(Collectors.toMap(keyExtractor, Function.identity()));

        int i = 0, j = 0;

        while (i < first.size() || j < second.size()) {
            final int saved = i + j;

            // Align elements with same key at current positions
            while (i < first.size() && j < second.size()) {
                K key1 = keyExtractor.apply(first.get(i));
                K key2 = keyExtractor.apply(second.get(j));
                if (!Objects.equals(key1, key2)) {
                    break;
                }
                // Keys match -> treat as shared
                sharedConsumer.accept(first.get(i), second.get(j));
                i++;
                j++;
            }

            // Consume first-only elements (not in second)
            while (i < first.size() && !secondEntries.containsKey(keyExtractor.apply(first.get(i)))) {
                firstOnlyConsumer.accept(first.get(i));
                i++;
            }

            // Consume second-only elements (not in first)
            while (j < second.size() && !firstEntries.containsKey(keyExtractor.apply(second.get(j)))) {
                secondOnlyConsumer.accept(second.get(j));
                j++;
            }

            // If no progress was made, fall back to drain both lists
            if (i + j == saved) {
                // Drain remaining of first
                for (; i < first.size(); i++) {
                    E e = first.get(i);
                    K k = keyExtractor.apply(e);
                    boolean isShared = secondEntries.containsKey(k);
                    if (isShared) {
                        sharedConsumer.accept(e, secondEntries.get(k));
                    } else {
                        firstOnlyConsumer.accept(e);
                    }
                }

                // Drain remaining of second, but skip already processed shared ones
                for (; j < second.size(); j++) {
                    E e = second.get(j);
                    K k = keyExtractor.apply(e);
                    if (!firstEntries.containsKey(k)) {
                        secondOnlyConsumer.accept(e);
                    }
                    // If it's shared, it was already handled in the first loop above
                }
            }
        }

//        return out;
    }

    private <K> void mergePreserveOrderNRA(List<? extends E> first, List<? extends E> second, Function<E, K> keyExtractor) {
        mergePreserveOrderRA(new ArrayList<>(first), new ArrayList<>(second), keyExtractor);
    }
}
