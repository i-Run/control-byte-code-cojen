/*
 *  Copyright 2004 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package cojen.classfile;

import java.util.Comparator;

/**
 * 
 * @author Brian S O'Neill
 */
// TODO: Remove this
class LocationRangeComparator implements Comparator {
    public final static Comparator SINGLETON = new LocationRangeComparator();

    private LocationRangeComparator() {
    }

    public int compare(Object a, Object b) {
        if (a == b) {
            return 0;
        }

        LocationRange lra = (LocationRange)a;
        LocationRange lrb = (LocationRange)b;

        int result = lra.getStartLocation().compareTo(lrb.getStartLocation());

        if (result == 0) {
            result = lra.getEndLocation().compareTo(lrb.getEndLocation());
        }

        return result;
    }
}
