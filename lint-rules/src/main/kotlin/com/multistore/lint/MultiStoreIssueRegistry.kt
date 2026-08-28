package com.multistore.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** Registry of MultiStore's custom lint checks. */
@Suppress("UnstableApiUsage")
class MultiStoreIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = listOf(
        ComposeHardcodedTextDetector.ISSUE,
    )

    override val api: Int = CURRENT_API

    override val minApi: Int = 14

    override val vendor: Vendor = Vendor(
        vendorName = "MultiStore",
        identifier = "com.multistore:lint-rules",
        feedbackUrl = "https://github.com/multistore/multistore/issues",
    )
}
