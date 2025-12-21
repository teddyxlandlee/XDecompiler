/*
 * Copyright 2023 teddyxlandlee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xland.ioutils.xdecompiler.util;

import java.text.StringCharacterIterator;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class TimeUtils {
    private static List<String> timeFormat0(Duration duration) {
        List<String> l = new ArrayList<>();
        int i;
        
        i = duration.toMillisPart();
        if (i == 0) { l.add("0ms"); return l; }
        l.add(i + "ms");
        
        i = duration.toSecondsPart();
        if (i == 0) return l;
        l.add(i + "s");
        
        i = duration.toMinutesPart();
        if (i == 0) return l;
        l.add(i + "min");
        
        i = duration.toHoursPart();
        if (i == 0) return l;
        l.add(i + "h");
        
        long j = duration.toDaysPart();
        if (j == 0L) return l;
        l.add(j + "d");
        
        return l;
    }
    
    public static String timeFormat(long nanos) {
        Duration duration = Duration.of(nanos, ChronoUnit.NANOS);
        return timeFormat(duration);
    }
    
    public static String timeFormat(Duration duration) {
        final List<String> l = timeFormat0(duration);
        Collections.reverse(l);
        return String.join(" ", l);
    }

    private static final Map<String, Long> DURATION_UNITS = Map.of(
            "ms", 1L,
            "s", 1000L,
            "sec", 1000L,
            "m", 1000L * 60,
            "mi", 1000L * 60,
            "min", 1000L * 60,
            "h", 1000L * 3600,
            "hr", 1000L * 3600,
            "d", 1000L * 86400
    );
    
    public static Duration fromString(String s) {
        StringCharacterIterator itr = new StringCharacterIterator(s);
        List<Map.Entry<String, Integer>> list = new ArrayList<>();

        char c;
        int start = 0;
        Integer integer = null;
        while (true) {
            c = itr.next();
            if ("0123456789".indexOf(c) >= 0) {
                if (integer != null) {  // !wasNumber
                    // stop suffixes
                    list.add(Map.entry(s.substring(start, (start = itr.getIndex())), integer));
                    integer = null;
                }
            } else if (c == StringCharacterIterator.DONE) {
                if (integer == null) {  // wasNumber
                    // treat the number as seconds
                    list.add(Map.entry("s", Integer.parseInt(s, start, itr.getEndIndex(), 10)));
                } else {
                    // treat as normal expressions
                    list.add(Map.entry(s.substring(start, itr.getEndIndex()), integer));
                }
                break;
            } else {
                if (integer == null) {  // wasNumber
                    // stop numbers
                    integer = Integer.parseInt(s, start, (start = itr.getIndex()), 10);
                }
            }
        }

        return Duration.ofMillis(
                list.stream().mapToLong(e -> Objects.requireNonNull(DURATION_UNITS.get(e.getKey()), e::getKey) * e.getValue())
                        .sum()
        );
    }

    private TimeUtils() {}
}