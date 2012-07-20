/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.analysis;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.*;

/**
 * Simple tests to ensure this factory is working
 */
public class TestPatternReplaceCharFilterFactory extends BaseTokenStreamTestCase {
  
  //           1111
  // 01234567890123
  // this is test.
  public void testNothingChange() throws IOException {
    final String BLOCK = "this is test.";
    PatternReplaceCharFilterFactory factory = new PatternReplaceCharFilterFactory();
    Map<String,String> args = new HashMap<String,String>();
    args.put("pattern", "(aa)\\s+(bb)\\s+(cc)");
    args.put("replacement", "$1$2$3");
    factory.init(args);
    CharFilter cs = factory.create(
          new StringReader( BLOCK ) );
    TokenStream ts = new MockTokenizer(cs, MockTokenizer.WHITESPACE, false);
    assertTokenStreamContents(ts,
        new String[] { "this", "is", "test." },
        new int[] { 0, 5, 8 },
        new int[] { 4, 7, 13 });
  }
  
  // 012345678
  // aa bb cc
  public void testReplaceByEmpty() throws IOException {
    final String BLOCK = "aa bb cc";
    PatternReplaceCharFilterFactory factory = new PatternReplaceCharFilterFactory();
    Map<String,String> args = new HashMap<String,String>();
    args.put("pattern", "(aa)\\s+(bb)\\s+(cc)");
    factory.init(args);
    CharFilter cs = factory.create(
          new StringReader( BLOCK ) );
    TokenStream ts = new MockTokenizer(cs, MockTokenizer.WHITESPACE, false);
    ts.reset();
    assertFalse(ts.incrementToken());
    ts.end();
    ts.close();
  }
  
  // 012345678
  // aa bb cc
  // aa#bb#cc
  public void test1block1matchSameLength() throws IOException {
    final String BLOCK = "aa bb cc";
    PatternReplaceCharFilterFactory factory = new PatternReplaceCharFilterFactory();
    Map<String,String> args = new HashMap<String,String>();
    args.put("pattern", "(aa)\\s+(bb)\\s+(cc)");
    args.put("replacement", "$1#$2#$3");
    factory.init(args);
    CharFilter cs = factory.create(
          new StringReader( BLOCK ) );
    TokenStream ts = new MockTokenizer(cs, MockTokenizer.WHITESPACE, false);
    assertTokenStreamContents(ts,
        new String[] { "aa#bb#cc" },
        new int[] { 0 },
        new int[] { 8 });
  }
}
