/*
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

package org.httprpc.beans;

import org.junit.Assert;
import org.junit.Test;

import static org.httprpc.WebService.listOf;
import static org.httprpc.WebService.mapOf;
import static org.httprpc.WebService.entry;

public class BeanAdapterTest {
    @Test
    public void testBeanAdapter() {
        BeanAdapter adapter = new BeanAdapter(new TestBean());

        Assert.assertEquals(mapOf(
            entry("a", 2L),
            entry("b", 4.0),
            entry("c", "abc"),
            entry("d", 0L),
            entry("e", mapOf(entry("i", true))),
            entry("f", listOf(mapOf(entry("i", true)))),
            entry("g", mapOf(entry("h", mapOf(entry("i", true)))))
        ), adapter);
    }
}
