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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimeUtils {
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
    
    public static Duration fromString(CharSequence s) {
        return Duration.parse(s);
    }
}