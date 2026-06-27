/*
 * Copyright (C) 2010 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.io;

public final class ObaApi {

    //private static final String TAG = "ObaApi";
    // Uninstantiatable
    private ObaApi() {
        throw new AssertionError();
    }

    public static final int OBA_OK = 200;

    public static final int OBA_BAD_REQUEST = 400;

    public static final int OBA_UNAUTHORIZED = 401;

    public static final int OBA_NOT_FOUND = 404;

    public static final int OBA_INTERNAL_ERROR = 500;

    public static final int OBA_BAD_GATEWAY = 502;

    public static final int OBA_OUT_OF_MEMORY = 666;

    public static final int OBA_IO_EXCEPTION = 700;

    public static final String VERSION1 = "1";

    public static final String VERSION2 = "2";

    /** The OBA REST API key appended to every request. */
    public static final String API_KEY = "v1_BktoDJ2gJlu6nLM6LsT9H8IUbWc=cGF1bGN3YXR0c0BnbWFpbC5jb20=";

    /** Preferences key holding the persisted per-install app UID (sent as {@code app_uid}). */
    public static final String APP_UID = "app_uid";
}
