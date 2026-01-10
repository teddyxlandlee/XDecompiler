package xland.ioutils.xdecompiler.util;

import java.util.*;
import java.util.function.Function;

public record Merger<E, R>(Function<E, R> firstOnlyTransformer,
                           Function<E, R> secondOnlyTransformer,
                           Function<E, R> sharedTransformer) {
    private static final Merger<?, ?> NO_TRANSFORM = new Merger<>(Function.identity(), Function.identity(), Function.identity());

    @SuppressWarnings("unchecked")
    public static <T> Merger<T, T> noTransform() {
        return (Merger<T, T>) NO_TRANSFORM;
    }

    public List<R> mergePreserveOrder(List<? extends E> first, List<? extends E> second) {
        if (first instanceof RandomAccess && second instanceof RandomAccess) {
            return mergePreserveOrderRA(first, second);
        } else {
            return mergePreserveOrderNRA(first, second);
        }
    }

    private List<R> mergePreserveOrderRA(List<? extends E> first, List<? extends E> second) {
        ArrayList<R> out = new ArrayList<>();
        HashSet<E> firstSet = new HashSet<>(first);
        HashSet<E> secondSet = new HashSet<>(second);

        int i = 0, j = 0;

        while (i < first.size() || j < second.size()) {
            // same index, same element
            while (i < first.size() && j < second.size() &&
                    Objects.equals(first.get(i), second.get(j))) {
                out.add(sharedTransformer.apply(first.get(i)));
                i++;
                j++;
            }

            // first-only
            while (i < first.size() && !secondSet.contains(first.get(i))) {
                out.add(firstOnlyTransformer.apply(first.get(i)));
                i++;
            }

            // second-only
            while (j < second.size() && !firstSet.contains(second.get(j))) {
                out.add(secondOnlyTransformer.apply(second.get(j)));
                j++;
            }

            // shared, unmatched index
            if (i < first.size() && j < second.size() &&
                    !Objects.equals(first.get(i), second.get(j))) {
                // add the first one, arbitrarily
                out.add(sharedTransformer.apply(first.get(i)));
                i++;
            }
        }

        return out;
    }

    private List<R> mergePreserveOrderNRA(List<? extends E> first, List<? extends E> second) {
        ArrayList<R> out = new ArrayList<>();
        HashSet<E> firstSet = new HashSet<>(first);
        HashSet<E> secondSet = new HashSet<>(second);

        ListIterator<? extends E> firstIter = first.listIterator();
        ListIterator<? extends E> secondIter = second.listIterator();

        while (firstIter.hasNext() && secondIter.hasNext()) {
            E firstElem = firstIter.next();
            E secondElem = secondIter.next();

            if (Objects.equals(firstElem, secondElem)) {
                out.add(sharedTransformer.apply(firstElem));
            } else {
                // rollback
                firstIter.previous();
                secondIter.previous();
                break;
            }
        }

        // remaining of first
        while (firstIter.hasNext()) {
            E elem = firstIter.next();
            if (!secondSet.contains(elem)) {
                out.add(firstOnlyTransformer.apply(elem));
            }
        }

        // remaining of second
        while (secondIter.hasNext()) {
            E elem = secondIter.next();
            if (!firstSet.contains(elem)) {
                out.add(secondOnlyTransformer.apply(elem));
            }
        }

        return out;
    }
}
