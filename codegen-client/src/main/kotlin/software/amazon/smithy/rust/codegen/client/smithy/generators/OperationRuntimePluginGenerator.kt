/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.smithy.customize.writeCustomizations
import software.amazon.smithy.rust.codegen.core.util.dq

/**
 * Generates operation-level runtime plugins
 */
class OperationRuntimePluginGenerator(
    private val codegenContext: ClientCodegenContext,
) {
    private val codegenScope = codegenContext.runtimeConfig.let { rc ->
        val runtimeApi = RuntimeType.smithyRuntimeApi(rc)
        val smithyTypes = RuntimeType.smithyTypes(rc)
        arrayOf(
            *preludeScope,
            "AuthSchemeOptionResolverParams" to runtimeApi.resolve("client::auth::AuthSchemeOptionResolverParams"),
            "BoxError" to RuntimeType.boxError(codegenContext.runtimeConfig),
            "ConfigBag" to RuntimeType.configBag(codegenContext.runtimeConfig),
            "Cow" to RuntimeType.Cow,
            "FrozenLayer" to smithyTypes.resolve("config_bag::FrozenLayer"),
            "IntoShared" to runtimeApi.resolve("shared::IntoShared"),
            "Layer" to smithyTypes.resolve("config_bag::Layer"),
            "RetryClassifiers" to runtimeApi.resolve("client::retries::RetryClassifiers"),
            "RuntimeComponentsBuilder" to RuntimeType.runtimeComponentsBuilder(codegenContext.runtimeConfig),
            "RuntimePlugin" to RuntimeType.runtimePlugin(codegenContext.runtimeConfig),
            "SharedAuthSchemeOptionResolver" to runtimeApi.resolve("client::auth::SharedAuthSchemeOptionResolver"),
            "SharedRequestSerializer" to runtimeApi.resolve("client::ser_de::SharedRequestSerializer"),
            "SharedResponseDeserializer" to runtimeApi.resolve("client::ser_de::SharedResponseDeserializer"),
            "StaticAuthSchemeOptionResolver" to runtimeApi.resolve("client::auth::static_resolver::StaticAuthSchemeOptionResolver"),
            "StaticAuthSchemeOptionResolverParams" to runtimeApi.resolve("client::auth::static_resolver::StaticAuthSchemeOptionResolverParams"),
        )
    }

    fun render(
        writer: RustWriter,
        operationShape: OperationShape,
        operationStructName: String,
        customizations: List<OperationCustomization>,
    ) {
        writer.rustTemplate(
            """
            impl #{RuntimePlugin} for $operationStructName {
                fn config(&self) -> #{Option}<#{FrozenLayer}> {
                    let mut cfg = #{Layer}::new(${operationShape.id.name.dq()});

                    cfg.store_put(#{SharedRequestSerializer}::new(${operationStructName}RequestSerializer));
                    cfg.store_put(#{SharedResponseDeserializer}::new(${operationStructName}ResponseDeserializer));

                    ${"" /* TODO(IdentityAndAuth): Resolve auth parameters from input for services that need this */}
                    cfg.store_put(#{AuthSchemeOptionResolverParams}::new(#{StaticAuthSchemeOptionResolverParams}::new()));

                    #{additional_config}

                    #{Some}(cfg.freeze())
                }

                fn runtime_components(&self, _: &#{RuntimeComponentsBuilder}) -> #{Cow}<'_, #{RuntimeComponentsBuilder}> {
                    #{Cow}::Owned(
                        #{RuntimeComponentsBuilder}::new(${operationShape.id.name.dq()})
                            #{interceptors}
                            #{retry_classifiers}
                    )
                }
            }

            #{runtime_plugin_supporting_types}
            """,
            *codegenScope,
            *preludeScope,
            "additional_config" to writable {
                writeCustomizations(
                    customizations,
                    OperationSection.AdditionalRuntimePluginConfig(
                        customizations,
                        newLayerName = "cfg",
                        operationShape,
                    ),
                )
            },
            "runtime_plugin_supporting_types" to writable {
                writeCustomizations(
                    customizations,
                    OperationSection.RuntimePluginSupportingTypes(customizations, "cfg", operationShape),
                )
            },
            "interceptors" to writable {
                writeCustomizations(
                    customizations,
                    OperationSection.AdditionalInterceptors(customizations, operationShape),
                )
            },
            "retry_classifiers" to writable {
                writeCustomizations(
                    customizations,
                    OperationSection.RetryClassifiers(customizations, operationShape),
                )
            },
        )
    }
}
