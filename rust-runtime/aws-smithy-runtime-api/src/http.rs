/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! HTTP request and response types

mod error;
mod headers;
mod request;

pub use error::HttpError;
pub use headers::{HeaderValue, Headers, HeadersIter};
pub use request::Request;
