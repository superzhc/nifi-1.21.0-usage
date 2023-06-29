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
package org.apache.nifi.kafka.shared.validation;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.kafka.shared.property.KafkaClientProperty;
import org.apache.nifi.kafka.shared.property.SaslMechanism;
import org.apache.nifi.kafka.shared.property.SecurityProtocol;
import org.apache.nifi.kafka.shared.property.provider.StandardKafkaPropertyProvider;
import org.apache.nifi.kerberos.KerberosCredentialsService;
import org.apache.nifi.kerberos.KerberosUserService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.KERBEROS_CREDENTIALS_SERVICE;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.KERBEROS_KEYTAB;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.KERBEROS_PRINCIPAL;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.KERBEROS_SERVICE_NAME;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.SASL_MECHANISM;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.SASL_PASSWORD;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.SASL_USERNAME;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.SECURITY_PROTOCOL;
import static org.apache.nifi.kafka.shared.component.KafkaClientComponent.SELF_CONTAINED_KERBEROS_USER_SERVICE;

/**
 * Custom Validation function for components supporting Kafka clients
 */
public class KafkaClientCustomValidationFunction implements Function<ValidationContext, Collection<ValidationResult>> {

    static final String JAVA_SECURITY_AUTH_LOGIN_CONFIG = "java.security.auth.login.config";

    private static final String ALLOW_EXPLICIT_KEYTAB = "NIFI_ALLOW_EXPLICIT_KEYTAB";

    private static final String JNDI_LOGIN_MODULE_CLASS = "JndiLoginModule";

    private static final String JND_LOGIN_MODULE_EXPLANATION = "The JndiLoginModule is not allowed in the JAAS configuration";

    private static final List<String> USERNAME_PASSWORD_SASL_MECHANISMS = Arrays.asList(
            SaslMechanism.PLAIN.getValue(),
            SaslMechanism.SCRAM_SHA_256.getValue(),
            SaslMechanism.SCRAM_SHA_512.getValue()
    );

    private static final List<String> SASL_PROTOCOLS = Arrays.asList(
            SecurityProtocol.SASL_PLAINTEXT.name(),
            SecurityProtocol.SASL_SSL.name()
    );

    @Override
    public Collection<ValidationResult> apply(final ValidationContext validationContext) {
        final Collection<ValidationResult> results = new ArrayList<>();
        validateLoginModule(validationContext, results);
        validateKerberosServices(validationContext, results);
        validateKerberosCredentials(validationContext, results);
        validateUsernamePassword(validationContext, results);
        validateAwsMskIamMechanism(validationContext, results);
        return results;
    }

    private void validateLoginModule(final ValidationContext validationContext, final Collection<ValidationResult> results) {
        final Optional<PropertyDescriptor> propertyDescriptorFound = validationContext.getProperties()
                .keySet()
                .stream()
                .filter(
                        propertyDescriptor -> KafkaClientProperty.SASL_JAAS_CONFIG.getProperty().equals(propertyDescriptor.getName())
                )
                .findFirst();
        if (propertyDescriptorFound.isPresent()) {
            final PropertyDescriptor propertyDescriptor = propertyDescriptorFound.get();
            final String saslJaasConfig = validationContext.getProperty(propertyDescriptor).getValue();
            if (saslJaasConfig.contains(JNDI_LOGIN_MODULE_CLASS)) {
                results.add(new ValidationResult.Builder()
                        .subject(propertyDescriptor.getName())
                        .valid(false)
                        .explanation(JND_LOGIN_MODULE_EXPLANATION)
                        .build());
            }
        }
    }

    private void validateKerberosServices(final ValidationContext validationContext, final Collection<ValidationResult> results) {
        final PropertyValue userServiceProperty = validationContext.getProperty(SELF_CONTAINED_KERBEROS_USER_SERVICE);
        final PropertyValue credentialsServiceProperty = validationContext.getProperty(KERBEROS_CREDENTIALS_SERVICE);
        final String principal = validationContext.getProperty(KERBEROS_PRINCIPAL).evaluateAttributeExpressions().getValue();
        final String keyTab = validationContext.getProperty(KERBEROS_KEYTAB).evaluateAttributeExpressions().getValue();

        if (userServiceProperty.isSet()) {
            if (credentialsServiceProperty.isSet()) {
                final String explanation = String.format("Cannot configure both [%s] and [%s]",
                        SELF_CONTAINED_KERBEROS_USER_SERVICE.getDisplayName(),
                        KERBEROS_CREDENTIALS_SERVICE.getDisplayName()
                );
                results.add(new ValidationResult.Builder()
                        .subject(KERBEROS_CREDENTIALS_SERVICE.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }

            if (isNotEmpty(principal) || isNotEmpty(keyTab)) {
                final String explanation = String.format("Cannot configure [%s] with [%s] or [%s]",
                        SELF_CONTAINED_KERBEROS_USER_SERVICE.getDisplayName(),
                        KERBEROS_PRINCIPAL.getDisplayName(),
                        KERBEROS_KEYTAB.getDisplayName()
                );
                results.add(new ValidationResult.Builder()
                        .subject(SELF_CONTAINED_KERBEROS_USER_SERVICE.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }
        } else if (credentialsServiceProperty.isSet()) {
            if (isNotEmpty(principal) || isNotEmpty(keyTab)) {
                final String explanation = String.format("Cannot configure [%s] with [%s] or [%s]",
                        KERBEROS_CREDENTIALS_SERVICE.getDisplayName(),
                        KERBEROS_PRINCIPAL.getDisplayName(),
                        KERBEROS_KEYTAB.getDisplayName()
                );
                results.add(new ValidationResult.Builder()
                        .subject(KERBEROS_CREDENTIALS_SERVICE.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }
        }

        final String allowExplicitKeytab = System.getenv(ALLOW_EXPLICIT_KEYTAB);
        if (Boolean.FALSE.toString().equalsIgnoreCase(allowExplicitKeytab) && (isNotEmpty(principal) || isNotEmpty(keyTab))) {
            final String explanation = String.format("Environment Variable [%s] disables configuring [%s] and [%s] properties",
                    ALLOW_EXPLICIT_KEYTAB,
                    KERBEROS_PRINCIPAL.getDisplayName(),
                    KERBEROS_KEYTAB.getDisplayName()
            );
            results.add(new ValidationResult.Builder()
                    .subject(KERBEROS_PRINCIPAL.getDisplayName())
                    .valid(false)
                    .explanation(explanation)
                    .build());
        }
    }

    private void validateKerberosCredentials(final ValidationContext validationContext, final Collection<ValidationResult> results) {
        final String saslMechanism = validationContext.getProperty(SASL_MECHANISM).getValue();
        final String securityProtocol = validationContext.getProperty(SECURITY_PROTOCOL).getValue();

        if (SaslMechanism.GSSAPI.name().equals(saslMechanism) && SASL_PROTOCOLS.contains(securityProtocol)) {
            final String serviceName = validationContext.getProperty(KERBEROS_SERVICE_NAME).evaluateAttributeExpressions().getValue();
            if (isEmpty(serviceName)) {
                final String explanation = String.format("[%s] required for [%s] value [%s]", KERBEROS_SERVICE_NAME.getDisplayName(), SASL_MECHANISM.getDisplayName(), SaslMechanism.GSSAPI);
                results.add(new ValidationResult.Builder()
                        .subject(KERBEROS_SERVICE_NAME.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }

            final String principal = validationContext.getProperty(KERBEROS_PRINCIPAL).evaluateAttributeExpressions().getValue();
            final String keyTab = validationContext.getProperty(KERBEROS_KEYTAB).evaluateAttributeExpressions().getValue();
            final String systemLoginConfig = System.getProperty(JAVA_SECURITY_AUTH_LOGIN_CONFIG);

            if (isEmpty(principal) && isNotEmpty(keyTab)) {
                final String explanation = String.format("[%s] required when configuring [%s]", KERBEROS_KEYTAB.getDisplayName(), KERBEROS_PRINCIPAL.getDisplayName());
                results.add(new ValidationResult.Builder()
                        .subject(KERBEROS_PRINCIPAL.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            } else if (isNotEmpty(principal) && isEmpty(keyTab)) {
                final String explanation = String.format("[%s] required when configuring [%s]", KERBEROS_PRINCIPAL.getDisplayName(), KERBEROS_KEYTAB.getDisplayName());
                results.add(new ValidationResult.Builder()
                        .subject(KERBEROS_KEYTAB.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }

            final KerberosUserService userService = validationContext.getProperty(SELF_CONTAINED_KERBEROS_USER_SERVICE).asControllerService(KerberosUserService.class);
            final KerberosCredentialsService credentialsService = validationContext.getProperty(KERBEROS_CREDENTIALS_SERVICE).asControllerService(KerberosCredentialsService.class);
            if (userService == null && credentialsService == null && isEmpty(principal) && isEmpty(keyTab) && isEmpty(systemLoginConfig)) {
                final String explanation = String.format("Kerberos Credentials not found in component properties or System Property [%s]", JAVA_SECURITY_AUTH_LOGIN_CONFIG);
                results.add(new ValidationResult.Builder()
                        .subject(SASL_MECHANISM.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }
        }
    }

    private void validateUsernamePassword(final ValidationContext validationContext, final Collection<ValidationResult> results) {
        final String saslMechanism = validationContext.getProperty(SASL_MECHANISM).getValue();

        if (USERNAME_PASSWORD_SASL_MECHANISMS.contains(saslMechanism)) {
            final String username = validationContext.getProperty(SASL_USERNAME).evaluateAttributeExpressions().getValue();
            if (username == null || username.isEmpty()) {
                final String explanation = String.format("[%s] required for [%s] values: %s", SASL_USERNAME.getDisplayName(), SASL_MECHANISM.getDisplayName(), USERNAME_PASSWORD_SASL_MECHANISMS);
                results.add(new ValidationResult.Builder()
                        .subject(SASL_USERNAME.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }

            final String password = validationContext.getProperty(SASL_PASSWORD).evaluateAttributeExpressions().getValue();
            if (password == null || password.isEmpty()) {
                final String explanation = String.format("[%s] required for [%s] values: %s", SASL_PASSWORD.getDisplayName(), SASL_MECHANISM.getDisplayName(), USERNAME_PASSWORD_SASL_MECHANISMS);
                results.add(new ValidationResult.Builder()
                        .subject(SASL_PASSWORD.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }
        }
    }

    private void validateAwsMskIamMechanism(final ValidationContext validationContext, final Collection<ValidationResult> results) {
        final PropertyValue saslMechanismProperty = validationContext.getProperty(SASL_MECHANISM);
        if (saslMechanismProperty.isSet()) {
            final SaslMechanism saslMechanism = SaslMechanism.getSaslMechanism(saslMechanismProperty.getValue());

            if (SaslMechanism.AWS_MSK_IAM == saslMechanism && !StandardKafkaPropertyProvider.isAwsMskIamCallbackHandlerFound()) {
                final String explanation = String.format("[%s] required class not found: Kafka modules must be compiled with AWS MSK enabled",
                        StandardKafkaPropertyProvider.SASL_AWS_MSK_IAM_CLIENT_CALLBACK_HANDLER_CLASS);

                results.add(new ValidationResult.Builder()
                        .subject(SASL_MECHANISM.getDisplayName())
                        .valid(false)
                        .explanation(explanation)
                        .build());
            }
        }
    }

    private boolean isEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    private boolean isNotEmpty(final String string) {
        return string != null && !string.isEmpty();
    }
}
