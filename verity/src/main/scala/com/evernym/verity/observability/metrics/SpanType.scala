package com.evernym.verity.observability.metrics

sealed trait SpanType
// todo probably could rework as Span.Type
case object ClientSpan extends SpanType
case object InternalSpan extends SpanType
case object DefaultSpan extends SpanType

