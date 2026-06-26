/*
 * Copyright (C) 2012 individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.io.test;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.junit.Test;
import org.onebusaway.android.io.JacksonSerializer;

import android.util.Log;

import static junit.framework.Assert.assertEquals;

/**
 * Tests use of the Jackson library for parsing JSON related to OBA
 */
public class JacksonTest extends ObaTestCase {

    private static final int mCode = 47421;

    private static final String mErrText = "Here is an error";

    protected JacksonSerializer mSerializer;

    @Test
    public void testPrimitive() {
        mSerializer = (JacksonSerializer) JacksonSerializer.getInstance();
        String test = mSerializer.toJson("abc");
        assertEquals("\"abc\"", test);

        test = mSerializer.toJson("a\\b\\c");
        assertEquals("\"a\\\\b\\\\c\"", test);
    }

    @Test
    public void testSerialization() {
        mSerializer = (JacksonSerializer) JacksonSerializer.getInstance();
        String errJson = mSerializer.serialize(new MockResponse());
        Log.d("*** test", errJson);
        String expected = String
                .format("{\"code\":%d,\"version\":\"2\",\"text\":\"%s\"}", mCode, mErrText);
        Log.d("*** expect", expected);
        assertEquals(expected, errJson);
    }

    @JsonPropertyOrder(value = {"code", "version", "text"})
    public class MockResponse {

        @SuppressWarnings("unused")
        private final String version;

        @SuppressWarnings("unused")
        private final int code;

        @SuppressWarnings("unused")
        private final String text;

        protected MockResponse() {
            version = "2";
            code = mCode;
            text = mErrText;
        }
    }
}
