/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.smithy.generators.protocol

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.documentShape
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.customize.OperationCustomization
import software.amazon.smithy.rust.codegen.smithy.customize.OperationSection
import software.amazon.smithy.rust.codegen.smithy.customize.writeCustomizations
import software.amazon.smithy.rust.codegen.smithy.generators.BuilderGenerator
import software.amazon.smithy.rust.codegen.smithy.generators.implBlock
import software.amazon.smithy.rust.codegen.smithy.generators.operationBuildError
import software.amazon.smithy.rust.codegen.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.util.inputShape

interface ProtocolBodyGenerator {
    data class BodyMetadata(val takesOwnership: Boolean)

    fun bodyMetadata(operationShape: OperationShape): BodyMetadata

    fun generateBody(writer: RustWriter, self: String, operationShape: OperationShape)
}

interface ProtocolTraitImplGenerator {
    fun generateTraitImpls(operationWriter: RustWriter, operationShape: OperationShape)
}

/**
 * Class providing scaffolding for HTTP based protocols that must build an HTTP request (headers / URL) and a body.
 */
open class ProtocolGenerator(
    codegenContext: CodegenContext,
    private val protocol: Protocol,
    private val makeOperationGenerator: MakeOperationGenerator,
    private val traitGenerator: ProtocolTraitImplGenerator,
) {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val symbolProvider = codegenContext.symbolProvider
    private val model = codegenContext.model

    private val codegenScope = arrayOf(
        "HttpRequestBuilder" to RuntimeType.HttpRequestBuilder,
        "OpBuildError" to codegenContext.runtimeConfig.operationBuildError(),
        "Request" to RuntimeType.Http("request::Request"),
        "RequestBuilder" to RuntimeType.HttpRequestBuilder,
        "SdkBody" to RuntimeType.sdkBody(codegenContext.runtimeConfig),
        "config" to RuntimeType.Config,
        "header_util" to CargoDependency.SmithyHttp(codegenContext.runtimeConfig).asType().member("header"),
        "http" to RuntimeType.http,
        "operation" to RuntimeType.operationModule(runtimeConfig),
    )

    fun renderOperation(
        operationWriter: RustWriter,
        inputWriter: RustWriter,
        operationShape: OperationShape,
        customizations: List<OperationCustomization>
    ) {
        val inputShape = operationShape.inputShape(model)
        val builderGenerator = BuilderGenerator(model, symbolProvider, operationShape.inputShape(model))
        builderGenerator.render(inputWriter)

        // TODO: One day, it should be possible for callers to invoke
        // buildOperationType* directly to get the type rather than depending
        // on these aliases.
        val operationTypeOutput = buildOperationTypeOutput(inputWriter, operationShape)
        val operationTypeRetry = buildOperationTypeRetry(inputWriter, customizations)
        val inputPrefix = symbolProvider.toSymbol(inputShape).name
        inputWriter.rust(
            """
            ##[doc(hidden)] pub type ${inputPrefix}OperationOutputAlias = $operationTypeOutput;
            ##[doc(hidden)] pub type ${inputPrefix}OperationRetryAlias = $operationTypeRetry;
            """
        )

        // impl OperationInputShape { ... }
        val operationName = symbolProvider.toSymbol(operationShape).name
        inputWriter.implBlock(inputShape, symbolProvider) {
            writeCustomizations(
                customizations,
                OperationSection.InputImpl(customizations, operationShape, inputShape, protocol)
            )
            makeOperationGenerator.generateMakeOperation(this, operationShape, customizations)
            rustBlockTemplate(
                "fn assemble(mut builder: #{RequestBuilder}, body: #{SdkBody}) -> #{Request}<#{SdkBody}>",
                *codegenScope
            ) {
                rustTemplate(
                    """
                    if let Some(content_length) = body.content_length() {
                        builder = #{header_util}::set_header_if_absent(
                                    builder,
                                    #{http}::header::CONTENT_LENGTH,
                                    content_length
                        );
                    }
                    builder.body(body).expect("should be valid request")
                    """,
                    *codegenScope
                )
            }

            // pub fn builder() -> ... { }
            builderGenerator.renderConvenienceMethod(this)
        }

        // pub struct Operation { ... }
        operationWriter.documentShape(operationShape, model)
        Attribute.Derives(setOf(RuntimeType.Clone, RuntimeType.Default, RuntimeType.Debug)).render(operationWriter)
        operationWriter.rustBlock("pub struct $operationName") {
            write("_private: ()")
        }
        operationWriter.implBlock(operationShape, symbolProvider) {
            builderGenerator.renderConvenienceMethod(this)

            rustBlock("pub fn new() -> Self") {
                rust("Self { _private: () }")
            }

            writeCustomizations(customizations, OperationSection.OperationImplBlock(customizations))
        }
        traitGenerator.generateTraitImpls(operationWriter, operationShape)
    }

    private fun buildOperationTypeOutput(writer: RustWriter, shape: OperationShape): String =
        writer.format(symbolProvider.toSymbol(shape))

    private fun buildOperationTypeRetry(writer: RustWriter, customizations: List<OperationCustomization>): String =
        customizations.mapNotNull { it.retryType() }.firstOrNull()?.let { writer.format(it) } ?: "()"
}