/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.lucene;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.lucene.util.Version;

/**
 * <p>
 * Lucene utils is a set of utilize written to make constructing Lucene queries simpler.</p>
 *
 * @author Jeremy Long <jeremy.long@owasp.org>
 */
public final class LuceneUtils {

    /**
     * The current version of Lucene being used. Declaring this one place so an upgrade doesn't require hunting through
     * the code base.
     */
    public static final Version CURRENT_VERSION = Version.LATEST;

    /**
     * Private constructor as this is a utility class.
     */
    private LuceneUtils() {
    }

    /**
     * Appends the text to the supplied StringBuilder escaping Lucene control characters in the process.
     *
     * @param buf a StringBuilder to append the escaped text to
     * @param text the data to be escaped
     */
    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings(
            value = "SF_SWITCH_NO_DEFAULT",
            justification = "The switch below does have a default.")
    public static void appendEscapedLuceneQuery(StringBuilder buf,
            final CharSequence text) {

        if (text == null || buf == null) {
            return;
        }

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '+':
                case '-':
                case '&':
                case '|':
                case '!':
                case '(':
                case ')':
                case '{':
                case '}':
                case '[':
                case ']':
                case '^':
                case '"':
                case '~':
                case '*':
                case '?':
                case ':':
                case '\\': //it is supposed to fall through here
                    buf.append('\\');
                default:
                    buf.append(c);
                    break;
            }
        }
    }

    /**
     * Escapes the text passed in so that it is treated as data instead of control characters.
     *
     * @param text data to be escaped
     * @return the escaped text.
     */
    public static String escapeLuceneQuery(final CharSequence text) {

        if (text == null) {
            return null;
        }

        int size = text.length();
        size = size >> 1;
        final StringBuilder buf = new StringBuilder(size);

        appendEscapedLuceneQuery(buf, text);

        return buf.toString();
    }
}
