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

import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author Jeremy Long <jeremy.long@owasp.org>
 */
public class LuceneUtilsTest {

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    /**
     * Test of appendEscapedLuceneQuery method, of class LuceneUtils.
     */
    @Test
    public void testAppendEscapedLuceneQuery() {
        StringBuilder buf = new StringBuilder();
        CharSequence text = "test encoding + - & | ! ( ) { } [ ] ^ \" ~ * ? : \\";
        String expResult = "test encoding \\+ \\- \\& \\| \\! \\( \\) \\{ \\} \\[ \\] \\^ \\\" \\~ \\* \\? \\: \\\\";
        LuceneUtils.appendEscapedLuceneQuery(buf, text);
        assertEquals(expResult, buf.toString());
    }

    /**
     * Test of appendEscapedLuceneQuery method, of class LuceneUtils.
     */
    @Test
    public void testAppendEscapedLuceneQuery_null() {
        StringBuilder buf = new StringBuilder();
        CharSequence text = null;
        LuceneUtils.appendEscapedLuceneQuery(buf, text);
        assertEquals(0, buf.length());
    }

    /**
     * Test of escapeLuceneQuery method, of class LuceneUtils.
     */
    @Test
    public void testEscapeLuceneQuery() {
        CharSequence text = "test encoding + - & | ! ( ) { } [ ] ^ \" ~ * ? : \\";
        String expResult = "test encoding \\+ \\- \\& \\| \\! \\( \\) \\{ \\} \\[ \\] \\^ \\\" \\~ \\* \\? \\: \\\\";
        String result = LuceneUtils.escapeLuceneQuery(text);
        assertEquals(expResult, result);
    }

    /**
     * Test of escapeLuceneQuery method, of class LuceneUtils.
     */
    @Test
    public void testEscapeLuceneQuery_null() {
        CharSequence text = null;
        String expResult = null;
        String result = LuceneUtils.escapeLuceneQuery(text);
        assertEquals(expResult, result);
    }
}