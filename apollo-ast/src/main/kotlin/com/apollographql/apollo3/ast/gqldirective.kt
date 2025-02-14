package com.apollographql.apollo3.ast

import com.apollographql.apollo3.annotations.ApolloInternal

fun List<GQLDirective>.findDeprecationReason() = firstOrNull { it.name == "deprecated" }
    ?.let {
      it.arguments
          ?.arguments
          ?.firstOrNull { it.name == "reason" }
          ?.value
          ?.let { value ->
            if (value !is GQLStringValue) {
              throw ConversionException("reason must be a string", it.sourceLocation)
            }
            value.value
          }
          ?: "No longer supported"
    }

fun List<GQLDirective>.findExperimentalReason() = firstOrNull { it.name == "experimental" }
    ?.let {
      it.arguments
          ?.arguments
          ?.firstOrNull { it.name == "reason" }
          ?.value
          ?.let { value ->
            if (value !is GQLStringValue) {
              throw ConversionException("reason must be a string", it.sourceLocation)
            }
            value.value
          }
          ?: "Experimental"
    }

@ApolloInternal
fun List<GQLDirective>.findTargetName() = firstOrNull { it.name == "experimental_targetName" }
    ?.let {
      it.arguments
          ?.arguments
          ?.firstOrNull { it.name == "name" }
          ?.value
          ?.let { value ->
            if (value !is GQLStringValue) {
              throw ConversionException("name must be a string", it.sourceLocation)
            }
            value.value
          }
    }


/**
 * @return `true` or `false` based on the `if` argument if the `optional` directive is present, `null` otherwise
 */
fun List<GQLDirective>.optionalValue(): Boolean? {
  val directive = firstOrNull { it.name == "optional" } ?: return null
  val argument = directive.arguments?.arguments?.firstOrNull { it.name == "if" }
  // "if" argument defaults to true
  return argument == null || argument.name == "if" && (argument.value as GQLBooleanValue).value
}

fun List<GQLDirective>.findNonnull() = any { it.name == "nonnull" }

fun GQLDirective.isApollo() = name in listOf("optional", "nonnull")
