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
package org.apache.nifi.web;

import org.apache.nifi.web.security.configuration.WebSecurityConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/**
 * Web Application Spring Configuration
 */
@Configuration
@Import({
        WebSecurityConfiguration.class
})
@ImportResource({"classpath:nifi-context.xml",
    "classpath:nifi-administration-context.xml",
    "classpath:nifi-authorizer-context.xml",
    "classpath:nifi-cluster-manager-context.xml",
    "classpath:nifi-cluster-protocol-context.xml",
    "classpath:nifi-web-api-context.xml"})
public class NiFiWebApiConfiguration {

    public NiFiWebApiConfiguration() {
        super();
    }

}
