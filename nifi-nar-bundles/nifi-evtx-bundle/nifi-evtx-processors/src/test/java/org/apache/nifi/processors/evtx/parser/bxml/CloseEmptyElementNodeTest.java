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

package org.apache.nifi.processors.evtx.parser.bxml;

import org.apache.nifi.processors.evtx.parser.BxmlNodeVisitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class CloseEmptyElementNodeTest extends BxmlNodeWithTokenTestBase {
    private CloseEmptyElementNode closeEmptyElementNode;

    @Override
    @BeforeEach
    public void setup() throws IOException {
        super.setup();
        closeEmptyElementNode = new CloseEmptyElementNode(testBinaryReaderBuilder.build(), chunkHeader, parent);
    }

    @Override
    protected byte getToken() {
        return BxmlNode.CLOSE_EMPTY_ELEMENT_TOKEN;
    }

    @Test
    public void testInit() {
        assertEquals(BxmlNode.CLOSE_EMPTY_ELEMENT_TOKEN, closeEmptyElementNode.getToken());
    }

    @Test
    public void testVisitor() throws IOException {
        BxmlNodeVisitor mock = mock(BxmlNodeVisitor.class);
        closeEmptyElementNode.accept(mock);
        verify(mock).visit(closeEmptyElementNode);
        verifyNoMoreInteractions(mock);
    }
}
