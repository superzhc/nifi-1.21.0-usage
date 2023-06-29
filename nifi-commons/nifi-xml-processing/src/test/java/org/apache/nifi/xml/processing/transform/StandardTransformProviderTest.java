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
package org.apache.nifi.xml.processing.transform;

import org.apache.nifi.xml.processing.ProcessingException;
import org.apache.nifi.xml.processing.ResourceProvider;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandardTransformProviderTest {
    private static final String METHOD = "xml";

    @Test
    void testTransformStandard() throws IOException {
        final StandardTransformProvider provider = new StandardTransformProvider();

        final DOMResult result = new DOMResult();

        try (final InputStream inputStream = ResourceProvider.getStandardDocument()) {
            final Source source = new StreamSource(inputStream);
            provider.transform(source, result);
        }

        assertDocumentNodeFound(result);
    }

    @Test
    void testTransformStandardDocumentTypeDeclaration() throws IOException {
        final StandardTransformProvider provider = new StandardTransformProvider();
        provider.setIndent(true);
        provider.setOmitXmlDeclaration(true);
        provider.setMethod(METHOD);

        final DOMResult result = new DOMResult();

        try (final InputStream inputStream = ResourceProvider.getStandardDocumentDocType()) {
            final Source source = new StreamSource(inputStream);
            provider.transform(source, result);
        }

        assertDocumentNodeFound(result);
    }

    @Test
    void testTransformStandardExternalEntityException() throws IOException {
        final StandardTransformProvider provider = new StandardTransformProvider();

        final DOMResult result = new DOMResult();

        try (final InputStream inputStream = ResourceProvider.getStandardDocumentDocTypeEntity()) {
            final Source source = new StreamSource(inputStream);
            final ProcessingException processingException = assertThrows(ProcessingException.class, () -> provider.transform(source, result));
            assertInstanceOf(TransformerException.class, processingException.getCause());
        }
    }

    private void assertDocumentNodeFound(final DOMResult result) {
        final Node node = result.getNode();
        assertNotNull(node);
        assertEquals(Node.DOCUMENT_NODE, node.getNodeType());
    }
}
